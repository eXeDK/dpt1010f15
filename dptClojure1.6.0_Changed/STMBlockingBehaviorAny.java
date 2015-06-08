// dpt1010f15
package clojure.lang;

import java.util.Set;

/**
 * Class for handling the blocking behavior used in retry
 */
class STMBlockingBehaviorAny extends STMBlockingBehavior {

    STMBlockingBehaviorAny(Set<Ref> refSet) {
        super(refSet);
    }

    /**
     * Handle the change in the given list of refs
     *
     * @param refSet   A list of ref that was changed
     */
    void handleChanged(Set<Ref> refSet) {
        int oldSize = this.refSet.size();
        this.refSet.removeAll(refSet);

        if (oldSize != this.refSet.size() || this.refSet.isEmpty()) {
            this.cdl.countDown();
        }
    }
}
