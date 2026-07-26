use codex_protocol::models::ResponseItem;
use uuid::Uuid;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TrajectoryNode {
    pub public_index: u64,
    hidden_id: String,
    pub summary: String,
    pub core_start_sequence: u64,
    pub core_end_sequence: u64,
    pub retrieval_start_sequence: u64,
    pub retrieval_end_sequence: u64,
    pub created_at_checkpoint: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HistorySlicePage {
    pub index: u64,
    pub content: String,
    pub total_chars: usize,
    pub truncated: bool,
    pub next_offset: Option<usize>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GovernanceProjection {
    pub user_intent: String,
    pub current_position: String,
    pub recent_episode: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TrajectoryError {
    EmptySummary,
    InvalidRange,
    UnknownIndex,
}

#[derive(Debug, Clone, Default)]
pub struct TrajectoryLedger {
    next_event_sequence: u64,
    next_public_index: u64,
    raw_events: Vec<(u64, ResponseItem)>,
    nodes: Vec<TrajectoryNode>,
    governance_projection: Option<GovernanceProjection>,
}

impl TrajectoryLedger {
    pub fn record_event(&mut self, item: &ResponseItem) -> u64 {
        let sequence = self.next_event_sequence;
        self.next_event_sequence = self.next_event_sequence.saturating_add(1);
        self.raw_events.push((sequence, item.clone()));
        sequence
    }

    pub fn append_node(
        &mut self,
        summary: impl Into<String>,
        core_start_sequence: u64,
        core_end_sequence: u64,
        retrieval_start_sequence: u64,
        retrieval_end_sequence: u64,
        checkpoint: u64,
    ) -> Result<u64, TrajectoryError> {
        let summary = summary.into().trim().to_string();
        if summary.is_empty() {
            return Err(TrajectoryError::EmptySummary);
        }
        if core_start_sequence > core_end_sequence
            || retrieval_start_sequence > retrieval_end_sequence
            || retrieval_start_sequence > core_start_sequence
            || retrieval_end_sequence < core_end_sequence
            || retrieval_end_sequence >= self.next_event_sequence
        {
            return Err(TrajectoryError::InvalidRange);
        }
        let public_index = self.next_public_index;
        self.next_public_index = self.next_public_index.saturating_add(1);
        self.nodes.push(TrajectoryNode {
            public_index,
            hidden_id: Uuid::new_v4().to_string(),
            summary,
            core_start_sequence,
            core_end_sequence,
            retrieval_start_sequence,
            retrieval_end_sequence,
            created_at_checkpoint: checkpoint,
        });
        Ok(public_index)
    }

    pub fn nodes(&self) -> &[TrajectoryNode] {
        &self.nodes
    }

    pub fn event_count(&self) -> u64 {
        self.next_event_sequence
    }

    pub fn set_governance_projection(&mut self, projection: GovernanceProjection) {
        self.governance_projection = Some(projection);
    }

    pub fn governance_projection(&self) -> Option<&GovernanceProjection> {
        self.governance_projection.as_ref()
    }

    pub fn history_slice_page(
        &self,
        index: u64,
        before: usize,
        after: usize,
        offset: usize,
        max_chars: usize,
    ) -> Result<HistorySlicePage, TrajectoryError> {
        let node_position = self
            .nodes
            .iter()
            .position(|node| node.public_index == index)
            .ok_or(TrajectoryError::UnknownIndex)?;
        let start_node = node_position.saturating_sub(before.min(2));
        let end_node = (node_position + after.min(2)).min(self.nodes.len().saturating_sub(1));
        let start = self.nodes[start_node].retrieval_start_sequence;
        let end = self.nodes[end_node].retrieval_end_sequence;
        let content = self.render_events(start, end);
        let total_chars = content.chars().count();
        let offset = offset.min(total_chars);
        let page = content
            .chars()
            .skip(offset)
            .take(max_chars)
            .collect::<String>();
        let next_offset =
            (offset + page.chars().count() < total_chars).then_some(offset + page.chars().count());
        Ok(HistorySlicePage {
            index,
            content: page,
            total_chars,
            truncated: next_offset.is_some(),
            next_offset,
        })
    }

    fn render_events(&self, start: u64, end: u64) -> String {
        self.raw_events
            .iter()
            .filter(|(sequence, _)| *sequence >= start && *sequence <= end)
            .map(|(_, item)| render_model_recoverable_event(item))
            .collect::<Vec<_>>()
            .join("\n")
    }
}

fn render_model_recoverable_event(item: &ResponseItem) -> String {
    let mut value = serde_json::to_value(item).unwrap_or_default();
    if let serde_json::Value::Object(object) = &mut value {
        object.remove("id");
        object.remove("call_id");
        object.remove("internal_chat_message_metadata_passthrough");
    }
    serde_json::to_string_pretty(&value).unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;
    use codex_protocol::models::ContentItem;

    fn message(text: &str) -> ResponseItem {
        ResponseItem::Message {
            id: Some("hidden-response-id".to_string()),
            role: "user".to_string(),
            content: vec![ContentItem::InputText {
                text: text.to_string(),
            }],
            phase: None,
            internal_chat_message_metadata_passthrough: None,
        }
    }

    #[test]
    fn append_only_nodes_recover_bounded_raw_history_without_hidden_id() {
        let mut ledger = TrajectoryLedger::default();
        ledger.record_event(&message("initial request"));
        ledger.record_event(&message("later correction"));
        let index = ledger
            .append_node("user corrected the request", 1, 1, 0, 1, 1)
            .unwrap();
        let page = ledger.history_slice_page(index, 0, 0, 0, 20).unwrap();
        assert_eq!(index, 0);
        assert!(page.truncated);
        assert!(!page.content.contains("hidden_id"));
        assert!(
            !ledger
                .history_slice_page(index, 0, 0, 0, 10_000)
                .unwrap()
                .content
                .contains("hidden-response-id")
        );
        assert_eq!(ledger.nodes()[0].public_index, 0);
    }
}
