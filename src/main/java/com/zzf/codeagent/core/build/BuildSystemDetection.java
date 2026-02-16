package com.zzf.codeagent.core.build;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public final class BuildSystemDetection {
    private final BuildSystemType primary;
    private final Set<BuildSystemType> candidates;
    private final BuildSystemType fromCommand;
    private final Set<BuildSystemType> fromWorkspace;

    public BuildSystemDetection(
            BuildSystemType primary,
            Set<BuildSystemType> candidates,
            BuildSystemType fromCommand,
            Set<BuildSystemType> fromWorkspace
    ) {
        this.primary = primary == null ? BuildSystemType.UNKNOWN : primary;
        this.candidates = candidates == null || candidates.isEmpty()
                ? Collections.singleton(BuildSystemType.UNKNOWN)
                : Collections.unmodifiableSet(EnumSet.copyOf(candidates));
        this.fromCommand = fromCommand == null ? BuildSystemType.UNKNOWN : fromCommand;
        this.fromWorkspace = fromWorkspace == null || fromWorkspace.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(EnumSet.copyOf(fromWorkspace));
    }

    public BuildSystemType getPrimary() {
        return primary;
    }

    public Set<BuildSystemType> getCandidates() {
        return candidates;
    }

    public BuildSystemType getFromCommand() {
        return fromCommand;
    }

    public Set<BuildSystemType> getFromWorkspace() {
        return fromWorkspace;
    }

    public boolean supports(BuildSystemType type) {
        if (type == null || type == BuildSystemType.UNKNOWN) {
            return false;
        }
        return candidates.contains(type);
    }
}
