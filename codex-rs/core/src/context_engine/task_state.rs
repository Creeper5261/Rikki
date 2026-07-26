use std::collections::HashMap;
use std::collections::HashSet;

use codex_protocol::models::ContentItem;
use codex_protocol::models::FunctionCallOutputPayload;
use codex_protocol::models::ResponseItem;

use super::ContextRetention;
use super::DeltaKind;
use super::DeltaNoteInput;
use super::EvidenceId;
use super::GraphBudget;
use super::GraphCandidateSet;
use super::GraphSeed;
use super::InMemoryRepoGraph;
use super::RepoEdgeKind;
use super::RepoNodeKind;
use crate::event_mapping::is_contextual_user_message_content;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ToolOutcomeStatus {
    Succeeded,
    Failed,
    Unknown,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ToolOutcomeCategory {
    Generic,
    FileRead,
    FileEdit,
    Git,
    Test,
    Build,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ToolOutcomeResolution {
    Open,
    Informational,
    SupersededBy(EvidenceId),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RequirementRevision {
    pub evidence_id: EvidenceId,
    pub response_item_id: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ToolOutcomeRecord {
    pub evidence_id: EvidenceId,
    pub response_item_id: Option<String>,
    pub call_id: String,
    pub status: ToolOutcomeStatus,
    pub tool_name: Option<String>,
    pub category: ToolOutcomeCategory,
    pub scope_paths: Vec<String>,
    pub observed_turn_id: Option<String>,
    pub resolution: ToolOutcomeResolution,
    pub output_excerpt: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ToolCallRecord {
    pub evidence_id: EvidenceId,
    pub response_item_id: Option<String>,
    pub call_id: String,
    pub tool_name: String,
    pub arguments: String,
    pub scope_paths: Vec<String>,
    pub category: ToolOutcomeCategory,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RepositoryRevision {
    pub evidence_id: EvidenceId,
    pub response_item_id: Option<String>,
    pub workdir: Option<String>,
    pub commit: String,
    pub observed_turn_id: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VerificationRecord {
    pub evidence_id: EvidenceId,
    pub response_item_id: Option<String>,
    pub category: ToolOutcomeCategory,
    pub status: ToolOutcomeStatus,
    pub resolution: ToolOutcomeResolution,
    pub command: String,
    pub scope_paths: Vec<String>,
    pub failed_cases: Vec<String>,
    pub diagnostics: Vec<String>,
    pub observed_turn_id: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ChangedFilesSnapshot {
    pub evidence_id: EvidenceId,
    pub response_item_id: Option<String>,
    pub workdir: Option<String>,
    pub files: Vec<String>,
    pub observed_turn_id: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SubgoalPlanStatus {
    Pending,
    InProgress,
    Completed,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SubgoalStatus {
    Pending,
    Active,
    AwaitingVerification,
    Closed,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SubgoalPlanItem {
    pub step: String,
    pub status: SubgoalPlanStatus,
    pub verification: Option<SubgoalVerificationRequirement>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SubgoalVerificationRequirement {
    pub command: String,
    pub scope_paths: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SubgoalPlanUpdate {
    pub evidence_id: EvidenceId,
    pub response_item_id: Option<String>,
    pub items: Vec<SubgoalPlanItem>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SubgoalRecord {
    pub id: String,
    pub step: String,
    pub status: SubgoalStatus,
    pub opened_evidence_id: EvidenceId,
    pub latest_plan_evidence_id: EvidenceId,
    pub response_item_id: Option<String>,
    pub verifier_evidence_id: Option<EvidenceId>,
    pub verification: Option<SubgoalVerificationRequirement>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct NavigationNodeDescription {
    pub node_ref: String,
    pub node_kind: String,
    pub related_paths: Vec<String>,
    pub reason: String,
    pub tool_output_indices: Vec<u64>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TaskDelta {
    RequirementRevision(RequirementRevision),
    SubgoalPlanUpdate(SubgoalPlanUpdate),
    ToolCall(ToolCallRecord),
    ToolOutcome(ToolOutcomeRecord),
}

impl TaskDelta {
    pub fn evidence_id(&self) -> EvidenceId {
        match self {
            Self::RequirementRevision(revision) => revision.evidence_id,
            Self::SubgoalPlanUpdate(update) => update.evidence_id,
            Self::ToolCall(call) => call.evidence_id,
            Self::ToolOutcome(outcome) => outcome.evidence_id,
        }
    }

    pub fn to_delta_note_input(&self) -> DeltaNoteInput {
        match self {
            Self::RequirementRevision(revision) => DeltaNoteInput {
                evidence_ids: vec![revision.evidence_id],
                kind: DeltaKind::Constraint,
                statement: revision.response_item_id.as_ref().map_or_else(
                    || "User requirement revision recorded.".to_string(),
                    |item_id| format!("User requirement revision recorded from {item_id}."),
                ),
                invalidated_assumption: None,
                scope_paths: Vec::new(),
                confidence: 1.0,
            },
            Self::SubgoalPlanUpdate(update) => DeltaNoteInput {
                evidence_ids: vec![update.evidence_id],
                kind: DeltaKind::Refinement,
                statement: format!(
                    "Plan update recorded with {} subgoal(s).",
                    update.items.len()
                ),
                invalidated_assumption: None,
                scope_paths: Vec::new(),
                confidence: 1.0,
            },
            Self::ToolOutcome(outcome) => DeltaNoteInput {
                evidence_ids: vec![outcome.evidence_id],
                kind: match outcome.status {
                    ToolOutcomeStatus::Failed => DeltaKind::OpenQuestion,
                    ToolOutcomeStatus::Succeeded | ToolOutcomeStatus::Unknown => {
                        DeltaKind::Refinement
                    }
                },
                statement: format!(
                    "Tool call {} completed with status {}.",
                    outcome.call_id,
                    tool_outcome_status_label(outcome.status)
                ),
                invalidated_assumption: None,
                scope_paths: outcome.scope_paths.clone(),
                confidence: 1.0,
            },
            Self::ToolCall(call) => DeltaNoteInput {
                evidence_ids: vec![call.evidence_id],
                kind: DeltaKind::Refinement,
                statement: format!("Tool call {} started as {}.", call.call_id, call.tool_name),
                invalidated_assumption: None,
                scope_paths: call.scope_paths.clone(),
                confidence: 1.0,
            },
        }
    }
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct TaskState {
    applied_evidence: HashSet<EvidenceId>,
    requirement_revisions: Vec<RequirementRevision>,
    subgoals: HashMap<String, SubgoalRecord>,
    tool_calls: HashMap<String, ToolCallRecord>,
    tool_outcomes: Vec<ToolOutcomeRecord>,
    repository_revisions: HashMap<String, RepositoryRevision>,
    verification_records: Vec<VerificationRecord>,
    changed_file_snapshots: HashMap<String, ChangedFilesSnapshot>,
    latest_dirty_file_snapshots: HashMap<String, ChangedFilesSnapshot>,
}

impl TaskState {
    pub fn apply(&mut self, mut delta: TaskDelta) -> Option<TaskDelta> {
        if !self.applied_evidence.insert(delta.evidence_id()) {
            return None;
        }

        if let TaskDelta::ToolOutcome(outcome) = &mut delta
            && let Some(call) = self.tool_calls.get(&outcome.call_id)
        {
            outcome.tool_name = Some(call.tool_name.clone());
            outcome.category = call.category;
            outcome.scope_paths = call.scope_paths.clone();
        }

        if let TaskDelta::ToolOutcome(outcome) = &delta
            && outcome.status == ToolOutcomeStatus::Succeeded
        {
            for previous in &mut self.tool_outcomes {
                if same_tool_scope(previous, outcome)
                    && previous.status == ToolOutcomeStatus::Failed
                    && previous.resolution == ToolOutcomeResolution::Open
                {
                    previous.resolution = ToolOutcomeResolution::SupersededBy(outcome.evidence_id);
                }
            }
        }

        if let TaskDelta::SubgoalPlanUpdate(update) = &delta {
            self.apply_subgoal_plan_update(update);
        }

        if let TaskDelta::ToolOutcome(outcome) = &delta
            && let Some(revision) = repository_revision_from_outcome(outcome, &self.tool_calls)
        {
            self.repository_revisions.insert(
                revision
                    .workdir
                    .clone()
                    .unwrap_or_else(|| "default".to_string()),
                revision,
            );
        }

        if let TaskDelta::ToolOutcome(outcome) = &delta
            && let Some(verification) = verification_from_outcome(outcome, &self.tool_calls)
        {
            if verification.status == ToolOutcomeStatus::Succeeded {
                for previous in &mut self.verification_records {
                    if same_verification_scope(previous, &verification)
                        && previous.status == ToolOutcomeStatus::Failed
                        && previous.resolution == ToolOutcomeResolution::Open
                    {
                        previous.resolution =
                            ToolOutcomeResolution::SupersededBy(verification.evidence_id);
                    }
                }
            }
            self.verification_records.push(verification);
        }

        if let TaskDelta::ToolOutcome(outcome) = &delta
            && let Some(changed_files) = changed_files_from_outcome(outcome, &self.tool_calls)
        {
            self.reopen_subgoals_with_stale_verifiers(&changed_files);
            if !changed_files.files.is_empty() {
                self.latest_dirty_file_snapshots.insert(
                    changed_files
                        .workdir
                        .clone()
                        .unwrap_or_else(|| "default".to_string()),
                    changed_files.clone(),
                );
            }
            self.changed_file_snapshots.insert(
                changed_files
                    .workdir
                    .clone()
                    .unwrap_or_else(|| "default".to_string()),
                changed_files,
            );
        }

        match &delta {
            TaskDelta::RequirementRevision(revision) => {
                self.requirement_revisions.push(revision.clone());
            }
            TaskDelta::SubgoalPlanUpdate(_) => {}
            TaskDelta::ToolCall(call) => {
                self.tool_calls.insert(call.call_id.clone(), call.clone());
            }
            TaskDelta::ToolOutcome(outcome) => {
                self.tool_outcomes.push(outcome.clone());
            }
        }
        Some(delta)
    }

    pub fn latest_requirement_revision(&self) -> Option<&RequirementRevision> {
        self.requirement_revisions.last()
    }

    pub fn requirement_revisions(&self) -> &[RequirementRevision] {
        &self.requirement_revisions
    }

    pub fn subgoals(&self) -> impl Iterator<Item = &SubgoalRecord> {
        self.subgoals.values()
    }

    pub fn active_subgoals(&self) -> impl Iterator<Item = &SubgoalRecord> {
        self.subgoals.values().filter(|subgoal| {
            matches!(
                subgoal.status,
                SubgoalStatus::Active | SubgoalStatus::AwaitingVerification
            )
        })
    }

    pub fn navigation_graph_signals(&self) -> Vec<GraphCandidateSet> {
        let mut graph = InMemoryRepoGraph::new();
        let mut file_nodes = HashMap::new();
        for snapshot in self.changed_file_snapshots.values() {
            for path in &snapshot.files {
                file_nodes
                    .entry(path.clone())
                    .or_insert_with(|| graph.add_node(RepoNodeKind::File, path.clone(), None));
            }
        }

        if file_nodes.is_empty() {
            return Vec::new();
        }

        for verification in self.verification_records.iter().filter(|record| {
            record.status == ToolOutcomeStatus::Failed
                && record.resolution == ToolOutcomeResolution::Open
        }) {
            for failed_case in &verification.failed_cases {
                let test_path = failed_case
                    .split_once("::")
                    .map_or(failed_case.as_str(), |(path, _)| path);
                let test_node = graph.add_node(RepoNodeKind::Test, test_path, None);
                for (file_path, file_node) in &file_nodes {
                    if test_likely_covers_file(test_path, file_path) {
                        graph.add_edge(
                            test_node,
                            *file_node,
                            RepoEdgeKind::TestCovers,
                            0.8,
                            "failed testcase name matches changed file stem",
                        );
                    }
                }
            }
        }

        file_nodes
            .keys()
            .take(4)
            .map(|path| {
                graph.markov_blanket_candidates(
                    GraphSeed::Path(path.clone()),
                    GraphBudget {
                        max_candidates: 4,
                        max_reason_edges: 2,
                    },
                )
            })
            .filter(|signal| !signal.candidates.is_empty())
            .collect()
    }

    pub fn describe_navigation_node(&self, node_ref: &str) -> Option<NavigationNodeDescription> {
        let path = node_ref.strip_prefix("repo-node://")?;
        if path.is_empty() {
            return None;
        }
        let mut tool_output_indices = Vec::new();
        let mut matches_open_failure = false;
        for verification in &self.verification_records {
            if verification.status != ToolOutcomeStatus::Failed
                || verification.resolution != ToolOutcomeResolution::Open
            {
                continue;
            }
            if verification.failed_cases.iter().any(|failed_case| {
                failed_case
                    .split_once("::")
                    .map_or(failed_case.as_str(), |(test_path, _)| test_path)
                    == path
            }) {
                matches_open_failure = true;
                tool_output_indices.push(verification.evidence_id.as_u64());
            }
        }
        if !matches_open_failure {
            return None;
        }

        let mut related_paths = self
            .changed_file_snapshots
            .values()
            .flat_map(|snapshot| {
                snapshot
                    .files
                    .iter()
                    .filter(|&file_path| test_likely_covers_file(path, file_path))
                    .cloned()
            })
            .collect::<Vec<_>>();
        related_paths.sort();
        related_paths.dedup();
        related_paths.truncate(8);
        for snapshot in self.changed_file_snapshots.values() {
            if snapshot
                .files
                .iter()
                .any(|file_path| related_paths.contains(file_path))
            {
                tool_output_indices.push(snapshot.evidence_id.as_u64());
            }
        }
        tool_output_indices.sort_unstable();
        tool_output_indices.dedup();

        Some(NavigationNodeDescription {
            node_ref: node_ref.to_string(),
            node_kind: "test".to_string(),
            related_paths,
            reason: "unresolved testcase name matches changed file stem".to_string(),
            tool_output_indices,
        })
    }

    pub fn tool_outcomes(&self) -> &[ToolOutcomeRecord] {
        &self.tool_outcomes
    }

    pub fn tool_calls(&self) -> &HashMap<String, ToolCallRecord> {
        &self.tool_calls
    }

    pub fn repository_revisions(&self) -> &HashMap<String, RepositoryRevision> {
        &self.repository_revisions
    }

    pub fn verification_records(&self) -> &[VerificationRecord] {
        &self.verification_records
    }

    pub fn changed_file_snapshots(&self) -> &HashMap<String, ChangedFilesSnapshot> {
        &self.changed_file_snapshots
    }

    pub fn latest_dirty_file_snapshots(&self) -> &HashMap<String, ChangedFilesSnapshot> {
        &self.latest_dirty_file_snapshots
    }

    pub fn failed_tool_outcomes(&self) -> impl Iterator<Item = &ToolOutcomeRecord> {
        self.tool_outcomes.iter().filter(|outcome| {
            outcome.status == ToolOutcomeStatus::Failed
                && outcome.resolution == ToolOutcomeResolution::Open
        })
    }

    pub fn unresolved_failed_verifications(&self) -> impl Iterator<Item = &VerificationRecord> {
        self.verification_records.iter().filter(|record| {
            record.status == ToolOutcomeStatus::Failed
                && record.resolution == ToolOutcomeResolution::Open
        })
    }

    pub fn stale_verifications(&self) -> impl Iterator<Item = &VerificationRecord> {
        self.verification_records
            .iter()
            .filter(|record| self.verification_is_stale(record))
    }

    pub fn retention_for_response_item(&self, item: &ResponseItem) -> ContextRetention {
        match item {
            ResponseItem::FunctionCallOutput { id, call_id, .. }
            | ResponseItem::CustomToolCallOutput { id, call_id, .. } => self
                .tool_outcomes
                .iter()
                .find(|outcome| outcome.response_item_id.as_ref() == id.as_ref())
                .map_or_else(
                    || self.retention_for_call_id(call_id),
                    retention_for_outcome,
                ),
            ResponseItem::FunctionCall { call_id, .. }
            | ResponseItem::CustomToolCall { call_id, .. } => self.retention_for_call_id(call_id),
            ResponseItem::AdditionalTools { .. }
            | ResponseItem::Message { .. }
            | ResponseItem::Reasoning { .. }
            | ResponseItem::LocalShellCall { .. }
            | ResponseItem::ToolSearchCall { .. }
            | ResponseItem::ToolSearchOutput { .. }
            | ResponseItem::WebSearchCall { .. }
            | ResponseItem::ImageGenerationCall { .. }
            | ResponseItem::Compaction { .. }
            | ResponseItem::ContextCompaction { .. }
            | ResponseItem::AgentMessage { .. }
            | ResponseItem::CompactionTrigger { .. }
            | ResponseItem::Other => ContextRetention::Optional,
        }
    }

    fn retention_for_call_id(&self, call_id: &str) -> ContextRetention {
        self.tool_outcomes
            .iter()
            .filter(|outcome| outcome.call_id == call_id)
            .map(retention_for_outcome)
            .max()
            .unwrap_or(ContextRetention::Optional)
    }

    pub fn render_context_summary(&self, max_tool_outcomes: usize) -> Option<String> {
        if self.requirement_revisions.is_empty()
            && self.tool_outcomes.is_empty()
            && self.subgoals.is_empty()
        {
            return None;
        }

        let failed_count = self.failed_tool_outcomes().count();
        let mut lines = vec![
            "TASK STATE SNAPSHOT".to_string(),
            format!(
                "requirements: revision_count={}",
                self.requirement_revisions.len(),
            ),
            format!(
                "tool_outcomes: total_count={} unresolved_failed_count={}",
                self.tool_outcomes.len(),
                failed_count
            ),
            format!(
                "repository_revisions: count={}",
                self.repository_revisions.len()
            ),
            format!(
                "verifications: total_count={} unresolved_failed_count={}",
                self.verification_records.len(),
                self.unresolved_failed_verifications().count()
            ),
            format!(
                "verification_freshness: stale_count={} dirty_worktree_count={}",
                self.stale_verifications().count(),
                self.latest_dirty_file_snapshots.len()
            ),
            format!(
                "changed_file_snapshots: count={}",
                self.changed_file_snapshots.len()
            ),
            format!(
                "subgoals: total_count={} active_count={} awaiting_verification_count={} closed_count={}",
                self.subgoals.len(),
                self.subgoals
                    .values()
                    .filter(|subgoal| subgoal.status == SubgoalStatus::Active)
                    .count(),
                self.subgoals
                    .values()
                    .filter(|subgoal| subgoal.status == SubgoalStatus::AwaitingVerification)
                    .count(),
                self.subgoals
                    .values()
                    .filter(|subgoal| subgoal.status == SubgoalStatus::Closed)
                    .count()
            ),
        ];

        let mut subgoals = self.subgoals.values().collect::<Vec<_>>();
        subgoals.sort_by_key(|subgoal| subgoal.latest_plan_evidence_id.0);
        for subgoal in subgoals.iter().rev().take(4).rev() {
            lines.push(format!(
                "subgoal: id={} status={} verifier_command={} verifier_scope={} verifier_tool_output_index={} step={}",
                bounded_field(&subgoal.id),
                subgoal_status_label(subgoal.status),
                subgoal
                    .verification
                    .as_ref()
                    .map(|verification| bounded_field(&verification.command))
                    .unwrap_or_else(|| "none".to_string()),
                subgoal
                    .verification
                    .as_ref()
                    .map(|verification| bounded_scope(&verification.scope_paths))
                    .unwrap_or_else(|| "none".to_string()),
                subgoal
                    .verifier_evidence_id
                    .map(|evidence_id| evidence_id.as_u64().to_string())
                    .unwrap_or_else(|| "none".to_string()),
                bounded_field(&subgoal.step)
            ));
        }

        for revision in self.repository_revisions.values().take(4) {
            lines.push(format!(
                "- workdir={} commit={} tool_output_index={}",
                revision
                    .workdir
                    .as_deref()
                    .map(bounded_field)
                    .unwrap_or_else(|| "default".to_string()),
                bounded_field(&revision.commit),
                revision.evidence_id.as_u64()
            ));
        }

        for snapshot in self.changed_file_snapshots.values().take(4) {
            lines.push(format!(
                "- changed_files workdir={} count={} files={} tool_output_index={}",
                snapshot
                    .workdir
                    .as_deref()
                    .map(bounded_field)
                    .unwrap_or_else(|| "default".to_string()),
                snapshot.files.len(),
                bounded_scope(&snapshot.files),
                snapshot.evidence_id.as_u64()
            ));
        }

        for verification in self.verification_records.iter().rev().take(4).rev() {
            lines.push(format!(
                "- verification category={} status={} resolution={} command={} scope={} failed_cases={} diagnostics={} tool_output_index={}",
                tool_outcome_category_label(verification.category),
                tool_outcome_status_label(verification.status),
                tool_outcome_resolution_label(verification.resolution),
                bounded_field(&verification.command),
                bounded_scope(&verification.scope_paths),
                bounded_scope(&verification.failed_cases),
                bounded_scope(&verification.diagnostics),
                verification.evidence_id.as_u64()
            ));
        }

        for outcome in self
            .tool_outcomes
            .iter()
            .rev()
            .take(max_tool_outcomes)
            .rev()
        {
            lines.push(format!(
                "- tool={} category={} status={} resolution={} scope={} tool_output_index={}",
                outcome
                    .tool_name
                    .as_deref()
                    .map(bounded_field)
                    .unwrap_or_else(|| "unknown".to_string()),
                tool_outcome_category_label(outcome.category),
                tool_outcome_status_label(outcome.status),
                tool_outcome_resolution_label(outcome.resolution),
                bounded_scope(&outcome.scope_paths),
                outcome.evidence_id.as_u64()
            ));
        }
        if self.tool_outcomes.len() > max_tool_outcomes {
            lines.push(format!(
                "- omitted_count={}",
                self.tool_outcomes.len() - max_tool_outcomes
            ));
        }

        Some(lines.join("\n"))
    }

    fn apply_subgoal_plan_update(&mut self, update: &SubgoalPlanUpdate) {
        for item in &update.items {
            let id = subgoal_id(&item.step);
            let opened_evidence_id = self
                .subgoals
                .get(&id)
                .map(|subgoal| subgoal.opened_evidence_id)
                .unwrap_or(update.evidence_id);
            let verifier_evidence_id =
                self.verifier_for_subgoal(opened_evidence_id, item.verification.as_ref());
            let subgoal = self
                .subgoals
                .entry(id.clone())
                .or_insert_with(|| SubgoalRecord {
                    id,
                    step: item.step.clone(),
                    status: SubgoalStatus::Pending,
                    opened_evidence_id: update.evidence_id,
                    latest_plan_evidence_id: update.evidence_id,
                    response_item_id: update.response_item_id.clone(),
                    verifier_evidence_id: None,
                    verification: item.verification.clone(),
                });
            if subgoal.status == SubgoalStatus::Closed
                && item.status != SubgoalPlanStatus::Completed
            {
                continue;
            }
            subgoal.step = item.step.clone();
            subgoal.latest_plan_evidence_id = update.evidence_id;
            subgoal.response_item_id = update.response_item_id.clone();
            subgoal.verification = item.verification.clone();
            match item.status {
                SubgoalPlanStatus::Pending => subgoal.status = SubgoalStatus::Pending,
                SubgoalPlanStatus::InProgress => subgoal.status = SubgoalStatus::Active,
                SubgoalPlanStatus::Completed => {
                    if item.verification.is_none() {
                        subgoal.status = SubgoalStatus::Closed;
                        subgoal.verifier_evidence_id = None;
                    } else if let Some(verifier_evidence_id) = verifier_evidence_id {
                        subgoal.status = SubgoalStatus::Closed;
                        subgoal.verifier_evidence_id = Some(verifier_evidence_id);
                    } else {
                        subgoal.status = SubgoalStatus::AwaitingVerification;
                        subgoal.verifier_evidence_id = None;
                    }
                }
            }
        }
    }

    fn verifier_for_subgoal(
        &self,
        opened_evidence_id: EvidenceId,
        requirement: Option<&SubgoalVerificationRequirement>,
    ) -> Option<EvidenceId> {
        let requirement = requirement?;
        if self.unresolved_failed_verifications().next().is_some() {
            return None;
        }
        self.verification_records
            .iter()
            .rev()
            .find(|record| {
                record.status == ToolOutcomeStatus::Succeeded
                    && record.evidence_id.0 > opened_evidence_id.0
                    && record.command == requirement.command
                    && record.scope_paths == requirement.scope_paths
                    && !self.verification_is_stale(record)
            })
            .map(|record| record.evidence_id)
    }

    fn verification_is_stale(&self, verification: &VerificationRecord) -> bool {
        self.latest_dirty_file_snapshots
            .get(&verification_workdir(verification))
            .is_some_and(|snapshot| {
                !snapshot.files.is_empty() && snapshot.evidence_id.0 > verification.evidence_id.0
            })
    }

    fn reopen_subgoals_with_stale_verifiers(&mut self, changed_files: &ChangedFilesSnapshot) {
        if changed_files.files.is_empty() {
            return;
        }
        let workdir = changed_files
            .workdir
            .clone()
            .unwrap_or_else(|| "default".to_string());
        let stale_verifier_ids = self
            .verification_records
            .iter()
            .filter(|verification| {
                verification_workdir(verification) == workdir
                    && verification.evidence_id.0 < changed_files.evidence_id.0
            })
            .map(|verification| verification.evidence_id)
            .collect::<HashSet<_>>();
        for subgoal in self.subgoals.values_mut() {
            if subgoal.status == SubgoalStatus::Closed
                && subgoal
                    .verifier_evidence_id
                    .is_some_and(|evidence_id| stale_verifier_ids.contains(&evidence_id))
            {
                subgoal.status = SubgoalStatus::AwaitingVerification;
                subgoal.verifier_evidence_id = None;
            }
        }
    }
}

pub fn deterministic_task_delta_for_response_item(
    evidence_id: EvidenceId,
    item: &ResponseItem,
) -> Option<TaskDelta> {
    match item {
        ResponseItem::Message {
            id, role, content, ..
        } if role == "user" && is_requirement_message(content) => {
            Some(TaskDelta::RequirementRevision(RequirementRevision {
                evidence_id,
                response_item_id: id.clone(),
            }))
        }
        ResponseItem::FunctionCallOutput {
            id,
            call_id,
            output,
            ..
        }
        | ResponseItem::CustomToolCallOutput {
            id,
            call_id,
            output,
            ..
        } => Some(TaskDelta::ToolOutcome(ToolOutcomeRecord {
            evidence_id,
            response_item_id: id.clone(),
            call_id: call_id.clone(),
            status: tool_outcome_status(output),
            tool_name: None,
            category: ToolOutcomeCategory::Generic,
            scope_paths: Vec::new(),
            observed_turn_id: item.turn_id().map(str::to_string),
            resolution: match tool_outcome_status(output) {
                ToolOutcomeStatus::Failed => ToolOutcomeResolution::Open,
                ToolOutcomeStatus::Succeeded | ToolOutcomeStatus::Unknown => {
                    ToolOutcomeResolution::Informational
                }
            },
            output_excerpt: bounded_output_text(output),
        })),
        ResponseItem::FunctionCall {
            id,
            name,
            namespace,
            arguments,
            call_id,
            ..
        } => {
            if name == "update_plan"
                && namespace.is_none()
                && let Some(items) = parse_subgoal_plan_items(arguments)
            {
                return Some(TaskDelta::SubgoalPlanUpdate(SubgoalPlanUpdate {
                    evidence_id,
                    response_item_id: id.clone(),
                    items,
                }));
            }
            let tool_name = namespace
                .as_ref()
                .map_or_else(|| name.clone(), |namespace| format!("{namespace}.{name}"));
            Some(TaskDelta::ToolCall(ToolCallRecord {
                evidence_id,
                response_item_id: id.clone(),
                call_id: call_id.clone(),
                scope_paths: structured_scope_paths(arguments),
                category: classify_tool_call(&tool_name, arguments),
                tool_name,
                arguments: arguments.clone(),
            }))
        }
        ResponseItem::CustomToolCall {
            id,
            name,
            input,
            call_id,
            ..
        } => Some(TaskDelta::ToolCall(ToolCallRecord {
            evidence_id,
            response_item_id: id.clone(),
            call_id: call_id.clone(),
            scope_paths: Vec::new(),
            category: classify_tool_call(name, input),
            tool_name: name.clone(),
            arguments: input.clone(),
        })),
        ResponseItem::AdditionalTools { .. }
        | ResponseItem::Message { .. }
        | ResponseItem::Reasoning { .. }
        | ResponseItem::LocalShellCall { .. }
        | ResponseItem::ToolSearchCall { .. }
        | ResponseItem::ToolSearchOutput { .. }
        | ResponseItem::WebSearchCall { .. }
        | ResponseItem::ImageGenerationCall { .. }
        | ResponseItem::Compaction { .. }
        | ResponseItem::ContextCompaction { .. }
        | ResponseItem::AgentMessage { .. }
        | ResponseItem::CompactionTrigger { .. }
        | ResponseItem::Other => None,
    }
}

fn is_requirement_message(content: &[ContentItem]) -> bool {
    !content.is_empty() && !is_contextual_user_message_content(content)
}

fn tool_outcome_status(output: &FunctionCallOutputPayload) -> ToolOutcomeStatus {
    match output.success {
        Some(true) => ToolOutcomeStatus::Succeeded,
        Some(false) => ToolOutcomeStatus::Failed,
        None => ToolOutcomeStatus::Unknown,
    }
}

fn tool_outcome_status_label(status: ToolOutcomeStatus) -> &'static str {
    match status {
        ToolOutcomeStatus::Succeeded => "succeeded",
        ToolOutcomeStatus::Failed => "failed",
        ToolOutcomeStatus::Unknown => "unknown",
    }
}

fn subgoal_status_label(status: SubgoalStatus) -> &'static str {
    match status {
        SubgoalStatus::Pending => "pending",
        SubgoalStatus::Active => "active",
        SubgoalStatus::AwaitingVerification => "awaiting_verification",
        SubgoalStatus::Closed => "closed",
    }
}

fn subgoal_id(step: &str) -> String {
    let normalized = step.split_whitespace().collect::<Vec<_>>().join(" ");
    format!("plan:{}", normalized.to_ascii_lowercase())
}

fn parse_subgoal_plan_items(arguments: &str) -> Option<Vec<SubgoalPlanItem>> {
    let value = serde_json::from_str::<serde_json::Value>(arguments).ok()?;
    let plan = value.get("plan")?.as_array()?;
    let mut items = Vec::with_capacity(plan.len());
    for item in plan {
        let step = item.get("step")?.as_str()?.trim();
        let status = match item.get("status")?.as_str()? {
            "pending" => SubgoalPlanStatus::Pending,
            "in_progress" => SubgoalPlanStatus::InProgress,
            "completed" => SubgoalPlanStatus::Completed,
            _ => return None,
        };
        if step.is_empty() {
            return None;
        }
        let verification = match item.get("verification") {
            Some(verification) => Some(parse_subgoal_verification_requirement(verification)?),
            None => None,
        };
        items.push(SubgoalPlanItem {
            step: bounded_field(step),
            status,
            verification,
        });
    }
    Some(items)
}

fn parse_subgoal_verification_requirement(
    verification: &serde_json::Value,
) -> Option<SubgoalVerificationRequirement> {
    let command = verification.get("command")?.as_str()?.trim();
    if command.is_empty() {
        return None;
    }
    let scope_paths = verification.get("scope").map_or_else(
        || Some(Vec::new()),
        |scope| {
            scope
                .as_array()?
                .iter()
                .map(|path| path.as_str().map(str::trim))
                .collect::<Option<Vec<_>>>()
                .map(|paths| {
                    paths
                        .into_iter()
                        .filter(|path| !path.is_empty())
                        .map(ToString::to_string)
                        .collect::<Vec<_>>()
                })
        },
    )?;
    Some(SubgoalVerificationRequirement {
        command: bounded_field(command),
        scope_paths,
    })
}

fn tool_outcome_category_label(category: ToolOutcomeCategory) -> &'static str {
    match category {
        ToolOutcomeCategory::Generic => "generic",
        ToolOutcomeCategory::FileRead => "file_read",
        ToolOutcomeCategory::FileEdit => "file_edit",
        ToolOutcomeCategory::Git => "git",
        ToolOutcomeCategory::Test => "test",
        ToolOutcomeCategory::Build => "build",
    }
}

fn tool_outcome_resolution_label(resolution: ToolOutcomeResolution) -> String {
    match resolution {
        ToolOutcomeResolution::Open => "open".to_string(),
        ToolOutcomeResolution::Informational => "informational".to_string(),
        ToolOutcomeResolution::SupersededBy(evidence_id) => {
            format!("superseded_by:{evidence_id}")
        }
    }
}

fn same_tool_scope(left: &ToolOutcomeRecord, right: &ToolOutcomeRecord) -> bool {
    left.tool_name.is_some()
        && left.tool_name == right.tool_name
        && left.category == right.category
        && left.scope_paths == right.scope_paths
}

fn retention_for_outcome(outcome: &ToolOutcomeRecord) -> ContextRetention {
    if outcome.status == ToolOutcomeStatus::Failed
        && outcome.resolution == ToolOutcomeResolution::Open
    {
        ContextRetention::Open
    } else {
        ContextRetention::Recoverable
    }
}

fn repository_revision_from_outcome(
    outcome: &ToolOutcomeRecord,
    tool_calls: &HashMap<String, ToolCallRecord>,
) -> Option<RepositoryRevision> {
    if outcome.category != ToolOutcomeCategory::Git
        || outcome.status != ToolOutcomeStatus::Succeeded
    {
        return None;
    }
    let call = tool_calls.get(&outcome.call_id)?;
    let arguments = call.arguments.to_ascii_lowercase();
    if !arguments.contains("rev-parse") || !arguments.contains("head") {
        return None;
    }
    let commit = outcome
        .output_excerpt
        .as_deref()?
        .lines()
        .map(str::trim)
        .find(|line| is_git_commit(line))?
        .to_string();
    Some(RepositoryRevision {
        evidence_id: outcome.evidence_id,
        response_item_id: outcome.response_item_id.clone(),
        workdir: outcome.scope_paths.first().cloned(),
        commit,
        observed_turn_id: outcome.observed_turn_id.clone(),
    })
}

fn is_git_commit(value: &str) -> bool {
    matches!(value.len(), 40 | 64) && value.bytes().all(|byte| byte.is_ascii_hexdigit())
}

fn bounded_output_text(output: &FunctionCallOutputPayload) -> Option<String> {
    const LIMIT: usize = 4_096;
    output
        .body
        .to_text()
        .map(|text| text.chars().take(LIMIT).collect())
}

fn verification_from_outcome(
    outcome: &ToolOutcomeRecord,
    tool_calls: &HashMap<String, ToolCallRecord>,
) -> Option<VerificationRecord> {
    if !matches!(
        outcome.category,
        ToolOutcomeCategory::Test | ToolOutcomeCategory::Build
    ) {
        return None;
    }
    let call = tool_calls.get(&outcome.call_id)?;
    let output = outcome.output_excerpt.as_deref().unwrap_or_default();
    Some(VerificationRecord {
        evidence_id: outcome.evidence_id,
        response_item_id: outcome.response_item_id.clone(),
        category: outcome.category,
        status: outcome.status,
        resolution: outcome.resolution,
        command: command_from_call(call),
        scope_paths: outcome.scope_paths.clone(),
        failed_cases: parse_failed_cases(output),
        diagnostics: parse_diagnostics(output),
        observed_turn_id: outcome.observed_turn_id.clone(),
    })
}

fn changed_files_from_outcome(
    outcome: &ToolOutcomeRecord,
    tool_calls: &HashMap<String, ToolCallRecord>,
) -> Option<ChangedFilesSnapshot> {
    if outcome.category != ToolOutcomeCategory::Git
        || outcome.status != ToolOutcomeStatus::Succeeded
    {
        return None;
    }
    let call = tool_calls.get(&outcome.call_id)?;
    let command = command_from_call(call).to_ascii_lowercase();
    if !command.contains("diff") || !command.contains("--name-only") {
        return None;
    }
    let files = outcome
        .output_excerpt
        .as_deref()
        .unwrap_or_default()
        .lines()
        .map(str::trim)
        .filter(|line| !line.is_empty() && !line.starts_with("diff --git"))
        .take(100)
        .map(str::to_string)
        .collect::<Vec<_>>();
    Some(ChangedFilesSnapshot {
        evidence_id: outcome.evidence_id,
        response_item_id: outcome.response_item_id.clone(),
        workdir: outcome.scope_paths.first().cloned(),
        files,
        observed_turn_id: outcome.observed_turn_id.clone(),
    })
}

fn same_verification_scope(left: &VerificationRecord, right: &VerificationRecord) -> bool {
    left.category == right.category
        && left.command == right.command
        && left.scope_paths == right.scope_paths
}

fn verification_workdir(verification: &VerificationRecord) -> String {
    verification
        .scope_paths
        .first()
        .cloned()
        .unwrap_or_else(|| "default".to_string())
}

fn command_from_call(call: &ToolCallRecord) -> String {
    serde_json::from_str::<serde_json::Value>(&call.arguments)
        .ok()
        .and_then(|arguments| arguments.get("command")?.as_str().map(str::to_string))
        .unwrap_or_else(|| call.arguments.clone())
}

fn parse_failed_cases(output: &str) -> Vec<String> {
    let mut cases = Vec::new();
    for line in output.lines().map(str::trim) {
        let candidate = if let Some(case) = line.strip_prefix("FAILED ") {
            Some(case.split_whitespace().next().unwrap_or(case))
        } else if let Some(case) = line.strip_prefix("ERROR ") {
            Some(case.split_whitespace().next().unwrap_or(case))
        } else {
            line.strip_suffix(" ... FAILED").map(|case| case)
        };
        if let Some(candidate) = candidate {
            push_unique_bounded(&mut cases, candidate, 12);
        }
    }
    cases
}

fn test_likely_covers_file(test_path: &str, file_path: &str) -> bool {
    let test_stem = std::path::Path::new(test_path)
        .file_stem()
        .and_then(|stem| stem.to_str())
        .unwrap_or_default()
        .strip_prefix("test_")
        .unwrap_or_default();
    let file_stem = std::path::Path::new(file_path)
        .file_stem()
        .and_then(|stem| stem.to_str())
        .unwrap_or_default();
    !test_stem.is_empty() && test_stem == file_stem
}

fn parse_diagnostics(output: &str) -> Vec<String> {
    let mut diagnostics = Vec::new();
    for line in output.lines().map(str::trim) {
        if line.contains("error[")
            || line.contains("error:")
            || line.starts_with("ERROR:")
            || line.starts_with("[ERROR]")
        {
            push_unique_bounded(&mut diagnostics, line, 12);
        }
    }
    diagnostics
}

fn push_unique_bounded(items: &mut Vec<String>, value: &str, max_items: usize) {
    if items.len() >= max_items || items.iter().any(|item| item == value) {
        return;
    }
    items.push(bounded_field(value));
}

fn classify_tool_call(tool_name: &str, arguments: &str) -> ToolOutcomeCategory {
    let searchable = format!("{tool_name} {arguments}").to_ascii_lowercase();
    if contains_any(
        &searchable,
        &[
            "pytest",
            "cargo test",
            "just test",
            "npm test",
            "pnpm test",
            "mvn test",
            "gradle test",
        ],
    ) {
        ToolOutcomeCategory::Test
    } else if contains_any(
        &searchable,
        &[
            "cargo build",
            "npm run build",
            "pnpm build",
            "mvn package",
            "mvn compile",
            "gradle build",
        ],
    ) {
        ToolOutcomeCategory::Build
    } else if searchable.contains("git ") || searchable.contains("git.exe ") {
        ToolOutcomeCategory::Git
    } else if contains_any(
        &searchable,
        &["apply_patch", "write_file", "edit_file", "create_file"],
    ) {
        ToolOutcomeCategory::FileEdit
    } else if contains_any(
        &searchable,
        &[
            "get-content",
            "read_file",
            "view_file",
            "view_image",
            "cat ",
            "sed ",
            "rg ",
            "get-childitem",
            "dir ",
            "ls ",
            "tree ",
            "findstr ",
            "read_mcp_resource",
            "list_mcp_resource",
            "get_repo_node",
            "search_workspace_files",
            "get_tool_output",
            "get_history_slice",
            "list_available_plugins",
        ],
    ) {
        ToolOutcomeCategory::FileRead
    } else {
        ToolOutcomeCategory::Generic
    }
}

fn contains_any(value: &str, needles: &[&str]) -> bool {
    needles.iter().any(|needle| value.contains(needle))
}

fn structured_scope_paths(arguments: &str) -> Vec<String> {
    let Ok(value) = serde_json::from_str::<serde_json::Value>(arguments) else {
        return Vec::new();
    };
    let mut paths = Vec::new();
    collect_scope_paths(&value, None, &mut paths);
    paths.sort();
    paths.dedup();
    paths.truncate(8);
    paths
}

fn collect_scope_paths(value: &serde_json::Value, key: Option<&str>, paths: &mut Vec<String>) {
    match value {
        serde_json::Value::String(text) if key.is_some_and(is_scope_key) => {
            if !text.trim().is_empty() {
                paths.push(text.trim().to_string());
            }
        }
        serde_json::Value::Array(items) => {
            for item in items {
                collect_scope_paths(item, key, paths);
            }
        }
        serde_json::Value::Object(values) => {
            for (child_key, child_value) in values {
                collect_scope_paths(child_value, Some(child_key), paths);
            }
        }
        serde_json::Value::Null
        | serde_json::Value::Bool(_)
        | serde_json::Value::Number(_)
        | serde_json::Value::String(_) => {}
    }
}

fn is_scope_key(key: &str) -> bool {
    matches!(
        key,
        "path" | "file_path" | "workdir" | "cwd" | "repo_path" | "workspace_root"
    )
}

fn bounded_scope(paths: &[String]) -> String {
    if paths.is_empty() {
        return "none".to_string();
    }
    paths
        .iter()
        .take(4)
        .map(|path| bounded_field(path))
        .collect::<Vec<_>>()
        .join(",")
}

fn bounded_field(value: &str) -> String {
    const LIMIT: usize = 160;
    if value.chars().count() <= LIMIT {
        return value.to_string();
    }
    value.chars().take(LIMIT).collect::<String>() + "..."
}

#[cfg(test)]
#[path = "task_state_tests.rs"]
mod tests;
