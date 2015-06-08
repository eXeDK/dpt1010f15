// dpt1010f15
package clojure.lang;

import java.util.Set;

/**
 * Class for handling the blocking behavior used in await
 */
class STMBlockingBehaviorFnAll extends STMBlockingBehavior {
    /**
     * Holds the function to be run
     */
    private IFn fn;

    /**
     * Holds the args to run the function with
     */
    private ISeq args;

    STMBlockingBehaviorFnAll(Set<Ref> refSet, IFn fn, ISeq args, long blockPoint) {
        super(refSet, blockPoint);

        this.fn = fn;
        this.args = args;
   }

    /**
     * Awaits the count down of this blocking behaviors CountDownLatch
     */
    protected boolean shouldUnblock() {
        boolean unblock = true;

        for (Ref ref : this.refSet) {
            if (ref.tvals.point < this.blockPoint) {
                unblock = false;
                break;
            }
        }
        return unblock && (Boolean) this.fn.applyTo(this.args);
    }
}
