package cloud.foundry.cli.logic.diff.change;

import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Base class for all change classes
 */
public abstract class CfChange {

    protected Object affectedObject;
    protected String propertyName;
    protected List<String> path;

    public Object getAffectedObject() {
        return affectedObject;
    }

    public String getPropertyName() {
        return propertyName;
    }

    public List<String> getPath() {
        return path;
    }

    public CfChange(Object affectedObject, String propertyName, List<String> path) {
        this.affectedObject = affectedObject;
        this.propertyName = propertyName;
        this.path = path;
    }

}
