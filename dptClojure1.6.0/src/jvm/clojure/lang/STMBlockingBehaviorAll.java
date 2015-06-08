// dpt1010f15
package clojure.lang;

import java.util.Set;

/**
 * Class for handling the blocking behavior used in await
 */
class STMBlockingBehaviorAll extends STMBlockingBehavior {

    STMBlockingBehaviorAll(Set<Ref> refSet) {
        super(refSet);
    }

    /**
     * Handle the change in the given list of refs
     *
     * @param refSet   A list of ref that was changed
     */
    void handleChanged(Set<Ref> refSet) {
        this.refSet.removeAll(refSet);

        if (this.refSet.isEmpty()) {
            this.cdl.countDown();
        }
    }
}
