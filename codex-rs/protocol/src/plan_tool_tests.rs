use super::UpdatePlanArgs;

#[test]
fn update_plan_args_accept_advertised_verification_contract() {
    let arguments = serde_json::json!({
        "plan": [{
            "step": "Run focused tests",
            "status": "pending",
            "verification": {
                "command": "pytest -q",
                "scope": ["D:/repo"]
            }
        }]
    });

    serde_json::from_value::<UpdatePlanArgs>(arguments)
        .expect("the handler arguments must accept the advertised tool schema");
}
