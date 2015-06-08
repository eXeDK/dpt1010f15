//dpt1010f15
/**
 *   Copyright (c) Rich Hickey. All rights reserved.
 *   The use and distribution terms for this software are covered by the
 *   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 *   which can be found in the file epl-v10.html at the root of this distribution.
 *   By using this software in any fashion, you are agreeing to be bound by
 * 	 the terms of this license.
 *   You must not remove this notice, or any other, from this software.
 **/

/* rich Jul 26, 2007 */

package clojure.lang;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;

@SuppressWarnings({"SynchronizeOnNonFinalField"})
public class LockingTransaction{

	// Constants
	public static final int RETRY_LIMIT = 10000;
	public static final int LOCK_WAIT_MSECS = 100;
	public static final long BARGE_WAIT_NANOS = 10 * 1000000;

	// Transaction states
	static final int RUNNING = 0;
	static final int COMMITTING = 1;
	static final int RETRY = 2;
	static final int KILLED = 3;
	static final int COMMITTED = 4;

	// Event handle keywords, public to allow use by macros, ensures change are reflected in both places
	public static final Keyword ONABORTKEYWORD = Keyword.intern("on-abort");
	public static final Keyword ONCOMMITKEYWORD = Keyword.intern("on-commit");
	public static final Keyword AFTERCOMMITKEYWORD = Keyword.intern("after-commit");

	// The actual transaction in a local thread
	final static ThreadLocal<LockingTransaction> transaction = new ThreadLocal<LockingTransaction>();

	// Definition of the retry error (Error is a throwable)
	static class RetryEx extends Error{
	}

	// Definition of the AbortException
	static class AbortException extends Exception{
	}

	// The info class, holds state, starting point and latch
	// Latch is "A synchronization aid that allows one or more threads to wait until a set of operations being performed in other threads completes." http://docs.oracle.com/javase/7/docs/api/java/util/concurrent/CountDownLatch.html
	public static class Info{
		final AtomicInteger status;
		final long startPoint;
		final CountDownLatch latch;

		// Initialize
		public Info(int status, long startPoint){
			this.status = new AtomicInteger(status);
			this.startPoint = startPoint;
			this.latch = new CountDownLatch(1);
		}

		// Check if the transaction is running, based on the status of the transaction
		public boolean running(){
			int s = status.get();
			return s == RUNNING || s == COMMITTING;
		}
	}

	// A commute function
	static class CFn {
		final IFn fn;
		final ISeq args;

		public CFn(IFn fn, ISeq args){
			this.fn = fn;
			this.args = args;
		}
	}

	// Total order on transactions
	// Transactions will consume a point for init, for each retry, and on commit if writing
	final private static AtomicLong lastPoint = new AtomicLong();

	void getReadPoint(){
		readPoint = lastPoint.incrementAndGet();
	}

	long getCommitPoint(){
		return lastPoint.incrementAndGet();
	}

    void stop(int status){
        if(info != null) {
            synchronized(info) {
                info.status.set(status);
                info.latch.countDown();
            }
            info = null;
            vals.clear();
            sets.clear();
            commutes.clear();
            //actions.clear();
        }
    }

	// LockingTransaction class properties
	Info info;
	long readPoint;
	long startPoint;
	long startTime;

	final RetryEx retryex = new RetryEx();
	final ArrayList<Agent.Action> actions = new ArrayList<Agent.Action>();
	// All transaction specific values to refs
	final HashMap<Ref, Object> vals = new HashMap<Ref, Object>();
	// Set of all refs, not the values
	final HashSet<Ref> sets = new HashSet<Ref>();
    // Set of all refs we have read
    final HashSet<Ref> gets = new HashSet<Ref>();
	// List of commutes, each commute consists of a ref and a commute function CFn
	final TreeMap<Ref, ArrayList<CFn>> commutes = new TreeMap<Ref, ArrayList<CFn>>();
    // Holds a list of all read locks we have on refs
	final HashSet<Ref> ensures = new HashSet<Ref>();
    // Static list for holding all blocking behaviors across all threads
    private final static Collection<STMBlockingBehavior> blockingBehaviors =
        Collections.newSetFromMap(new ConcurrentHashMap<STMBlockingBehavior, Boolean>());
    // Holds information about how to unblock if the transaction is blocked by retry
    private STMBlockingBehavior blockingBehavior = null;
	// Holds all event listeners for this transaction
	private final HashMap<Keyword, ArrayList<EventFn>> eventListeners = new HashMap<Keyword, ArrayList<EventFn>>();
	// Boolean to enable orElse semantics
	private boolean orElseRunning = false;

	// Function to try to get a write lock on Ref ref
	// If it cant get it, it throws the retry exception
	void tryWriteLock(Ref ref){
		try {
			// Try to wait on the lock
			if(!ref.lock.writeLock().tryLock(LOCK_WAIT_MSECS, TimeUnit.MILLISECONDS))
				throw retryex;
		} catch(InterruptedException e) {
			throw retryex;
		}
	}

	//returns the most recent val
	Object lock(Ref ref){
		//can't upgrade readLock, so release it
		releaseIfEnsured(ref);

		// Whether or not the lock on the ref is unlocked
		boolean unlocked = true;
		try {
			// Try to get the write lock, if not, an exception is thrown
			tryWriteLock(ref);
			unlocked = false;

			// Check if the Ref's TVal is set and the point of the TVal is newer than the point of the transaction
			if(ref.tvals != null && ref.tvals.point > readPoint)
				throw retryex;
			// Get the transactional info of the Ref
			Info refinfo = ref.tinfo;

			// Write lock conflict
			// Check if the ref transaction is running and not the same as the current transaction
			if(refinfo != null && refinfo != info && refinfo.running()) {
				// Try to barge the other transaction if it is newer than this one
				if( ! barge(refinfo)) {
					// If this is not possible, unlock the writelock and blockAndBail
					ref.lock.writeLock().unlock();
					unlocked = true;
					return blockAndBail(refinfo);
				}
			}
			// Set the transaction of the Ref to this transaction
			ref.tinfo = info;
			return ref.tvals == null ? null : ref.tvals.val;
		} finally {
			if( ! unlocked)
				ref.lock.writeLock().unlock();
		}
	}

	private Object blockAndBail(Info refinfo){
        // Disables block and bail when doing an or else block
        if(this.orElseRunning) {
            throw retryex;
        }

		// Stop prior to blocking
		stop(RETRY);
		try {
			// Block the thread until the latch reaches a count of zero (0)
			refinfo.latch.await(LOCK_WAIT_MSECS, TimeUnit.MILLISECONDS);
		} catch(InterruptedException e) {
			//ignore
		}
		throw retryex;
	}

	// If a lock on a ref is held (found in ensures) then remove it from ensures and release the lock
	private void releaseIfEnsured(Ref ref){
		if(ensures.contains(ref)) {
			ensures.remove(ref);
			ref.lock.readLock().unlock();
		}
	}

	// Abort the transaction
	void abort() throws AbortException{
        // Anything to run on abort should also be run when we terminate the transaction
		EventManager.runEvents(LockingTransaction.ONABORTKEYWORD, this.eventListeners, null);
		stop(KILLED);
		throw new AbortException();
	}

	// Bool whether or not the barge time is elapsed
	private boolean bargeTimeElapsed(){
		return System.nanoTime() - startTime > BARGE_WAIT_NANOS;
	}

	// Barge the transaction which is the owner of refinfo
	private boolean barge(Info refinfo){
		boolean barged = false;
		// If this transaction is older, try to abort the other
		if(bargeTimeElapsed() && startPoint < refinfo.startPoint) {
			barged = refinfo.status.compareAndSet(RUNNING, KILLED);
			if(barged)
				refinfo.latch.countDown();
		}
		return barged;
	}

	// Check if a transaction is running
	static LockingTransaction getEx(){
		LockingTransaction t = transaction.get();
		if(t == null || t.info == null)
			throw new IllegalStateException("No transaction running");
		return t;
	}

	// Public function of checking if the transaction is running
	static public boolean isRunning(){
		return getRunning() != null;
	}

	// Get the current running transaction, if any
	static LockingTransaction getRunning(){
		LockingTransaction t = transaction.get();
		if(t == null || t.info == null)
			return null;
		return t;
	}

	// Run a specific Callable function fn in this transaction
	static public Object runInTransaction(Callable fn) throws Exception{
		// Get the this transaction and create identifier for the return value of fn
		LockingTransaction t = transaction.get();
		Object ret;
		// Check is the the transaction exists
		if(t == null) {
			// If not, set the this transaction to a new LockingTransaction
			transaction.set(t = new LockingTransaction());
			// Try to run fn and get the return value
			try {
				ret = t.run(fn);
			} finally {
				// No matter what, remove the transaction afterwards
				transaction.remove();
			}
		} else {
			// If the transaction exists, call fn or run the transaction if the transactional info is set
			if(t.info != null) {
				ret = fn.call();
			} else {
				ret = t.run(fn);
			}
		}

		return ret;
	}

	static class Notify{
		final public Ref ref;
		final public Object oldval;
		final public Object newval;

		Notify(Ref ref, Object oldval, Object newval){
			this.ref = ref;
			this.oldval = oldval;
			this.newval = newval;
		}
	}

	Object run(Callable fn) throws Exception{
		// Initialising variables for done state and return value
		boolean done = false;
		Object ret = null;
		// List of refs we have write lock on
		ArrayList<Ref> locked = new ArrayList<Ref>();
		ArrayList<Notify> notify = new ArrayList<Notify>();

		// As long as the retry limit has not been reached and the transaction is not done
		for(int i = 0; !done && i < RETRY_LIMIT; i++) {
            // If a blocking behavior is set for this transaction, block the transaction
            if (this.blockingBehavior != null) {
                this.blockingBehavior.await();
                LockingTransaction.blockingBehaviors.remove(this.blockingBehavior);
                this.blockingBehavior = null;
            }
            // Clears the set of read refs
            gets.clear();

			try {
				// Set starting point and start time when i == 1
				getReadPoint();
				if (i == 0) {
					startPoint = readPoint;
					startTime = System.nanoTime();
				}
				// Set the status of the info of the transaction to RUNNING
				info = new Info(RUNNING, startPoint);
				// Get the return value
				ret = fn.call();
				// Make sure no one has killed us before this point, and can't from now on
				if (info.status.compareAndSet(RUNNING, COMMITTING)) {
					// Run through all commutes
					for(Map.Entry<Ref, ArrayList<CFn>> e : commutes.entrySet()) {
						// Ref is the key, CFn is the value
						Ref ref = e.getKey();
						// If the ref is already in the set of refs skip it (for some reason)
						if(sets.contains(ref)) continue;

						// Check if we already have the read lock on the ref
						boolean wasEnsured = ensures.contains(ref);
						// Can't upgrade readLock, so release it
						releaseIfEnsured(ref);
						// Try to get the write lock and add to locked
						tryWriteLock(ref);
						locked.add(ref);
						// If we had read lock on the ref, the ref is already in transaction and its point if newer than ours
						// Throw a retry exception
						if(wasEnsured && ref.tvals != null && ref.tvals.point > readPoint)
							throw retryex;

						// Get the transaction info of the ref
						Info refinfo = ref.tinfo;
						// If the ref is in a transaction, which is not this one any it is running, try to barge in on it - otherwise retry
						if(refinfo != null && refinfo != info && refinfo.running()) {
							if(!barge(refinfo))
								throw retryex;
						}
						// If the ref has a TVal then get it, otherwise null
						Object val = (ref.tvals == null) ? null : ref.tvals.val;
						// Add the value to the set of vals
						vals.put(ref, val);
						// For each commute on the ref, apply the function to the value in this transaction
						for(CFn f : e.getValue()) {
							vals.put(ref, f.fn.applyTo(RT.cons(vals.get(ref), f.args)));
						}
					}
					// Get write lock for all refs in the set of refs
					for(Ref ref : sets) {
						tryWriteLock(ref);
						locked.add(ref);
					}

					// Validate and enqueue notifications
					// Validators is run here
					for(Map.Entry<Ref, Object> e : vals.entrySet()) {
						Ref ref = e.getKey();
						ref.validate(ref.getValidator(), e.getValue());
					}

					// Notify all listeners for "on-commit" event
					PersistentHashSet persistentSets = PersistentHashSet.create(RT.seq(this.vals.keySet()));
					EventManager.runEvents(LockingTransaction.ONCOMMITKEYWORD, this.eventListeners, persistentSets);

					// At this point, all values calculated, all refs to be written locked
					// No more client code to be called
					// Get commit point
					long commitPoint = getCommitPoint();
					for(Map.Entry<Ref, Object> e : vals.entrySet()) {
						Ref ref = e.getKey();
						Object oldval = ref.tvals == null ? null : ref.tvals.val;
						Object newval = e.getValue();
						int hcount = ref.histCount();

						// If the ref have no TVal, set this one
						if(ref.tvals == null) {
							ref.tvals = new Ref.TVal(newval, commitPoint);
						} else if((ref.faults.get() > 0 && hcount < ref.maxHistory)
								|| hcount < ref.minHistory) {
							// There are faults in the ref and the history is a correct value
							// Set the TVal for the ref
							ref.tvals = new Ref.TVal(newval, commitPoint, ref.tvals);
							ref.faults.set(0);
						} else {
							// The ref already has a TVal, set this as the next one
							// Set the new value and commit point
							ref.tvals = ref.tvals.next;
							ref.tvals.val = newval;
							ref.tvals.point = commitPoint;
						}
						// Notify all watches
						if(ref.getWatches().count() > 0)
							notify.add(new Notify(ref, oldval, newval));
					}

					// If we got to this point, we are done
					// Set the status of the info of the transaction to COMMITTED
					done = true;
					info.status.set(COMMITTED);
				}
			} catch(RetryEx ex) {
				// Ignore the exception so we retry rather than fall out
				EventManager.runEvents(LockingTransaction.ONABORTKEYWORD, this.eventListeners, null);
			} catch(AbortException ae) {
                // We want to terminate the transaction but have nothing to return
                return null;
			} catch(Exception exception) {
				EventManager.runEvents(LockingTransaction.ONABORTKEYWORD, this.eventListeners, null);
                throw exception;
			} finally {
				// Do this no matter what
				// Unlock all write locks
				for(int k = locked.size() - 1; k >= 0; --k) {
					locked.get(k).lock.writeLock().unlock();
				}
				locked.clear();
				// Unlock all read locks
				for(Ref r : ensures) {
					r.lock.readLock().unlock();
				}
				ensures.clear();

				// Are we done or should we retry?
				stop(done ? COMMITTED : RETRY);
                try {
                    // Re-dispatch out of transaction
                    if(done) {
                        // Notify watches
                        for(Notify n : notify) {
                            n.ref.notifyWatches(n.oldval, n.newval);
                        }
                        // Run agents, I think this is the ones used in the transaction as side-effects
                        for(Agent.Action action : actions) {
                            Agent.dispatchAction(action);
						}

						// All blocking transactions are notified of the changed refs
                        for (STMBlockingBehavior blockingBehavior : LockingTransaction.blockingBehaviors) {
                            blockingBehavior.handleChanged();
                        }

						// Executes after-commit events
						EventManager.runEvents(LockingTransaction.AFTERCOMMITKEYWORD, this.eventListeners, null);
                    }
				} finally {
					// Clear watches and agents
					notify.clear();
					actions.clear();
					eventListeners.clear();
					// Clear sets here instead of in stop() since we need the references
					// when handling changes for blocking behaviors
					sets.clear();
				}
			}
		}
		// No more retries available, throw a runtime exception
		if(!done)
			throw Util.runtimeException("Transaction failed after reaching retry limit");
		return ret;
	}

	public void enqueue(Agent.Action action){
		actions.add(action);
	}

	HashMap<Keyword, ArrayList<EventFn>> getEventListeners() {
		return this.eventListeners;
	}

	Object doGet(Ref ref){
		if(!info.running())
			throw retryex;
        // Add this ref to the set of gets
        gets.add(ref);
		if(vals.containsKey(ref))
			return vals.get(ref);
		try {
			ref.lock.readLock().lock();
			if(ref.tvals == null)
				throw new IllegalStateException(ref.toString() + " is unbound.");
			Ref.TVal ver = ref.tvals;
			do {
				if(ver.point <= readPoint)
					return ver.val;
			} while ((ver = ver.prior) != ref.tvals);
		} finally {
			ref.lock.readLock().unlock();
		}
		// no version of val precedes the read point
		ref.faults.incrementAndGet();
		throw retryex;
	}

	Object doSet(Ref ref, Object val){
		if(!info.running())
			throw retryex;
		if (commutes.containsKey(ref))
			throw new IllegalStateException("Can't set after commute");
		if(!sets.contains(ref)) {
			lock(ref);
			sets.add(ref);
		}
		vals.put(ref, val);
		return val;
	}

	void doEnsure(Ref ref){
		if(!info.running())
			throw retryex;
		if(ensures.contains(ref))
			return;
		ref.lock.readLock().lock();

		//someone completed a write after our snapshot
		if(ref.tvals != null && ref.tvals.point > readPoint) {
			ref.lock.readLock().unlock();
			throw retryex;
		}

		Info refinfo = ref.tinfo;

		//writer exists
		if(refinfo != null && refinfo.running()) {
			ref.lock.readLock().unlock();

			//not us, ensure is doomed
			if(refinfo != info) {
				blockAndBail(refinfo);
			}
		} else {
			ensures.add(ref);
		}
	}

	Object doCommute(Ref ref, IFn fn, ISeq args) {
		if(!info.running())
			throw retryex;
		if(!vals.containsKey(ref)) {
			Object val = null;
			try {
				ref.lock.readLock().lock();
				val = ref.tvals == null ? null : ref.tvals.val;
			} finally {
				ref.lock.readLock().unlock();
			}
			vals.put(ref, val);
		}
		ArrayList<CFn> fns = commutes.get(ref);
		if(fns == null) {
			commutes.put(ref, fns = new ArrayList<CFn>());
		}
		fns.add(new CFn(fn, args));
		Object ret = fn.applyTo(RT.cons(vals.get(ref), args));
		vals.put(ref, ret);
		return ret;
	}

    void doBlocking(HashSet<Ref> refs, IFn fn, ISeq args, boolean blockOnAll) throws InterruptedException, RetryEx {
        if ( ! info.running()) {
            throw retryex;
        }

        if (refs == null) {
            refs = new HashSet();
            refs.addAll(this.gets);
        }
        if (blockOnAll) {
			if (fn != null) {
				this.blockingBehavior = new STMBlockingBehaviorFnAll(refs, fn, args, this.readPoint);
			} else {
				this.blockingBehavior = new STMBlockingBehaviorAll(refs, this.readPoint);
			}
        } else {
			if (fn != null) {
				this.blockingBehavior = new STMBlockingBehaviorFnAny(refs, fn, args, this.readPoint);
			} else {
                this.blockingBehavior = new STMBlockingBehaviorAny(refs, this.readPoint);
			}
        }
        LockingTransaction.blockingBehaviors.add(this.blockingBehavior);
        throw retryex;
    }

    Object doOrElse(ArrayList<IFn> fns) {
        this.orElseRunning = true;
        for (IFn fn : fns) {
            try {
                return fn.invoke();
            } catch (RetryEx ex) {
				// We ignore the exception to allow the next function to execute
			}
        }
		this.orElseRunning = false;
        throw retryex;
    }
}
