// dpt1010f15
package clojure.lang;

import java.util.Set;

/**
 * Class for handling the blocking behavior used in retry
 */
class STMBlockingBehaviorFnAny extends STMBlockingBehavior {
    /**
     * Holds the function to be run
     */
    private IFn fn;

    /**
     * Holds the args to run the function with
     */
    private ISeq args;

    STMBlockingBehaviorFnAny(Set<Ref> refSet, IFn fn, ISeq args, long blockPoint) {
        super(refSet, blockPoint);

        this.fn = fn;
        this.args = args;
    }

    /**
     * Return a boolean if the blocking behavior should unblock
     */
    protected boolean shouldUnblock() {
        for (Ref ref : this.refSet) {
            if (ref.tvals.point > this.blockPoint) {
                break;
            }
        }
        return (Boolean) this.fn.applyTo(this.args);
    }
}
