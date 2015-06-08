// dpt1010f15
package clojure.lang;

import java.util.Set;

/**
 * Class for handling the blocking behavior used in await
 */
class STMBlockingBehaviorAll extends STMBlockingBehavior {

    STMBlockingBehaviorAll(Set<Ref> refSet, long blockPoint) {
        super(refSet, blockPoint);
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
        return unblock;
    }
}
