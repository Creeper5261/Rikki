package com.zzf.rikki.core.build;
import java.util.Collections;
import java.util.List;
public class BuildSystemDetection {
    private final BuildSystemType primary;
    public BuildSystemDetection(BuildSystemType primary) { this.primary = primary != null ? primary : BuildSystemType.UNKNOWN; }
    public BuildSystemType getPrimary() { return primary; }
    public boolean supports(BuildSystemType type) { return primary == type; }
    public List<BuildSystemType> getCandidates() { return Collections.singletonList(primary); }
}
