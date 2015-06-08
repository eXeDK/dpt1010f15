// dpt1010f15
package clojure.lang;

import java.lang.RuntimeException;

/**
 * A thunk for use with lazy expressions
 */
public class Thunk implements IDeref {
    private IFn fn;
    private Object value;

    /**
     * LazyEvalException for when trying to dereference an unexecuted Thunk
     */
    static class LazyEvalException extends RuntimeException {
        public LazyEvalException(String msg){
            super(msg);
        }
    }

    /**
     *  Default constructor
     *
     * @param fn            The function to be run on execution
     * @throws Exception    Throws an exception when no transaction is running
     */
    public Thunk(IFn fn) throws Exception {
        this.fn = fn;
        LockingTransaction t = LockingTransaction.getEx();
        if (t.isRunningThunks()) {
            throw new LazyEvalException("Lazy expression nested inside another lazy expression");
        }
        t.doThunk(this);
    }

    /**
     * Execute the thunk and set the value of the Thunk
     */
    void run() throws Exception {
        // Throws IllegalStateException if no transaction is running
        LockingTransaction.getEx();
        this.value = fn.call();
    }

    /**
     * Dereference the Thunk and get the value (if any).
     * If not yet executed a LazyEvalException will be thrown
     *
     * @return Object
     * @throws LazyEvalException
     */
    public Object deref() throws LazyEvalException {
        if (this.value == null) {
            throw new LazyEvalException("Thunk dereferenced outside lazy expression");
        }
        return this.value;
    }
}
