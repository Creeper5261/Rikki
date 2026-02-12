import json
import requests
import sys
import time

BASE_URL = "http://localhost:8080"

def test_chat_stream():
    print("Testing /api/agent/chat/stream...")
    url = f"{BASE_URL}/api/agent/chat/stream"
    payload = {
        "message": "Hello, who are you?",
        "sessionID": "test-session-" + str(int(time.time()))
    }
    
    try:
        response = requests.post(url, json=payload, stream=True, timeout=30)
        if response.status_code != 200:
            print(f"FAILED: Status code {response.status_code}")
            print(response.text)
            return False
            
        print("Connected to SSE stream. Receiving events...")
        has_content = False
        for line in response.iter_lines():
            if line:
                decoded_line = line.decode('utf-8')
                print(f"Event: {decoded_line}")
                if "message_part" in decoded_line or "text" in decoded_line:
                    has_content = True
                
                # Check for errors in the stream
                if "error" in decoded_line.lower():
                    print(f"FAILED: Error in stream: {decoded_line}")
                    return False
        
        if not has_content:
            print("FAILED: No content received in stream")
            return False
            
        print("SUCCESS: SSE stream completed successfully")
        return True
    except Exception as e:
        print(f"FAILED: Exception occurred: {e}")
        return False

if __name__ == "__main__":
    success = test_chat_stream()
    if not success:
        sys.exit(1)
    print("\nAll tests passed!")
    sys.exit(0)
