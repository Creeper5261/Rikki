use crate::session::tests::make_session_and_context;
use codex_protocol::models::ContentItem;
use codex_protocol::models::ResponseItem;

#[tokio::test]
async fn session_history_slice_uses_public_index_and_pages_raw_events() {
    let (session, turn) = make_session_and_context().await;
    let first = ResponseItem::Message {
        id: Some("hidden-history-id".to_string()),
        role: "user".to_string(),
        content: vec![ContentItem::InputText {
            text: "first request".to_string(),
        }],
        phase: None,
        internal_chat_message_metadata_passthrough: None,
    };
    let second = ResponseItem::Message {
        id: Some("hidden-history-id-2".to_string()),
        role: "user".to_string(),
        content: vec![ContentItem::InputText {
            text: "corrected request".to_string(),
        }],
        phase: None,
        internal_chat_message_metadata_passthrough: None,
    };
    session
        .record_conversation_items(&turn, &[first, second])
        .await;
    let index = session
        .append_context_trajectory_node("user corrected the request".to_string(), 1, 1, 0, 1, 1)
        .await
        .expect("valid raw-event range should append");
    let first_page = session
        .hydrate_history_slice_page(index, 0, 0, 0, 12)
        .await
        .expect("public index should resolve internally");
    assert_eq!(first_page.index, 0);
    assert!(first_page.truncated);
    assert!(first_page.next_offset.is_some());
    assert!(!first_page.content.contains("hidden_id"));
    assert!(
        session
            .hydrate_history_slice_page(99, 0, 0, 0, 12)
            .await
            .is_err()
    );
}
