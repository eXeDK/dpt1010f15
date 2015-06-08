// dpt1010f15
package clojure.lang;

/**
 * EventFn class for storing functions for later execution
 */
public class EventFn {
    final private IFn fn;
    final private ISeq args;
    final private boolean deleteAfterRun;

    /**
     * Constructor allowing to delete function when it has been executed once
     *
     * @param fn             The function to be executed on run
     * @param args           The arguments to the function given as fn
     * @param deleteAfterRun Whether or not to delete the function when it has been executed once
     */
    EventFn(IFn fn, ISeq args, boolean deleteAfterRun) {
        this.fn = fn;
        this.args = args;
        this.deleteAfterRun = deleteAfterRun;
    }

    /**
     * Indicates if the thunk should be deleted after being executed
     *
     * @return Returns the return value of the function executed
     */
    boolean deleteAfterRun() {
        return this.deleteAfterRun;
    }

    /**
     * Execute the thunk and returns the value computed
     *
     * @return Returns the return value of the function executed
     */
    Object run() {
        return fn.applyTo(this.args);
    }
}
