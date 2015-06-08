// dpt1010f15
package clojure.lang;

import java.util.Set;
import java.util.concurrent.CountDownLatch;

/**
 * Abstract class for blocking behaviors in relation with retry functionality
 */
abstract class STMBlockingBehavior {
    protected Set<Ref> refSet;
    protected CountDownLatch cdl;
    protected long blockPoint;

    /**
     *  Default constructor
     *
     * @param refSet   A list of ref to block on
     */
    STMBlockingBehavior(Set<Ref> refSet, long blockPoint) {
        this.refSet = refSet;
        this.blockPoint = blockPoint;
        this.cdl = new CountDownLatch(1);
    }

    /**
     * Awaits the count down of this blocking behaviors CountDownLatch
     */
    void await() throws InterruptedException {
        if ( ! shouldUnblock()) {
            this.cdl.await();
        }
    }

    /**
     * Unblock the blocking behavior based on handleChanged
     */
    void handleChanged() {
        if (shouldUnblock()) {
            this.cdl.countDown();
        }
    }

    /**
     * Return a boolean if the blocking behavior should unblock
     */
    abstract protected boolean shouldUnblock();
}
