package com.zzf.codeagent.idea;

import java.util.ArrayList;
import java.util.List;

final class PendingCommandInfo {
    String id;
    String command;
    String description;
    String workdir;
    String workspaceRoot;
    String sessionId;
    long timeoutMs;
    String riskLevel;
    String riskCategory;
    String commandFamily;
    boolean strictApproval;
    List<String> reasons = new ArrayList<>();
}
