package dev.jack.boppatches.build;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

public final class BopWorkspaceDiff {

    private final Set<String> overlayClassPrefixes;
    private final Set<String> deletedClassPrefixes;
    private final Set<String> overlayResources;
    private final Set<String> deletedResources;

    BopWorkspaceDiff(
            Set<String> overlayClassPrefixes,
            Set<String> deletedClassPrefixes,
            Set<String> overlayResources,
            Set<String> deletedResources) {
        this.overlayClassPrefixes = immutableCopy(overlayClassPrefixes);
        this.deletedClassPrefixes = immutableCopy(deletedClassPrefixes);
        this.overlayResources = immutableCopy(overlayResources);
        this.deletedResources = immutableCopy(deletedResources);
    }

    public Set<String> getOverlayClassPrefixes() {
        return overlayClassPrefixes;
    }

    public Set<String> getDeletedClassPrefixes() {
        return deletedClassPrefixes;
    }

    public Set<String> getOverlayResources() {
        return overlayResources;
    }

    public Set<String> getDeletedResources() {
        return deletedResources;
    }

    private static Set<String> immutableCopy(Set<String> source) {
        return Collections.unmodifiableSet(new TreeSet<>(source));
    }
}
