// BSD License (http://www.galagosearch.org/license)
package org.galagosearch.tupleflow.execution;

/**
 * Represents an output step in a TupleFlow stage.
 * 
 * @author trevor
 */
public class OutputStep extends Step {
    String id;

    public OutputStep(String id) {
        this.id = id;
    }

    public OutputStep(FileLocation location, String id) {
        this.id = id;
        this.location = location;
    }

    public String getId() {
        return id;
    }
}
