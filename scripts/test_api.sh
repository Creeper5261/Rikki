#!/bin/bash
# Test script for code-agent API

BASE_URL="http://localhost:8080"
SESSION_ID="test-session-$(date +%s)"

echo "Testing /api/agent/chat/stream..."
curl -N -X POST "$BASE_URL/api/agent/chat/stream" \
     -H "Content-Type: application/json" \
     -d "{\"message\": \"Hello, what can you do?\", \"sessionID\": \"$SESSION_ID\"}" \
     --max-time 60

if [ $? -eq 0 ]; then
    echo -e "\n\nSUCCESS: API call finished."
else
    echo -e "\n\nFAILED: API call failed."
    exit 1
fi
