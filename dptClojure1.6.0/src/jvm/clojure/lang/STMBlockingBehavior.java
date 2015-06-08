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

    /**
     *  Default constructor
     *
     * @param refSet   A list of ref to block on
     */
    STMBlockingBehavior(Set<Ref> refSet) {
        this.refSet = refSet;
        this.cdl = new CountDownLatch(1);
    }

    /**
     * Awaits the count down of this blocking behaviors CountDownLatch
     */
    void await() throws InterruptedException {
        this.cdl.await();
    }

    /**
     * Handle the change in the given list of refs
     *
     * @param refSet   A list of ref that was changed
     */
    abstract void handleChanged(Set<Ref> refSet);
}
