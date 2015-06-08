// dpt1010f15
package clojure.lang;

import java.util.Set;

/**
 * Class for handling the blocking behavior used in retry
 */
class STMBlockingBehaviorAny extends STMBlockingBehavior {

    STMBlockingBehaviorAny(Set<Ref> refSet, long blockPoint) {
        super(refSet, blockPoint);
    }

    /**
     * Return a boolean if the blocking behavior should unblock
     */
    protected boolean shouldUnblock() {
        for (Ref ref : this.refSet) {
            if (ref.tvals.point > this.blockPoint) {
                return true;
            }
        }
        return false;
    }
}
