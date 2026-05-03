# gemma4_service

An Android application that runs Google's **Gemma 4 4BE** model as an exported Foreground Service, accessible from other apps via AIDL IPC.

## Features

- **Foreground Service** (`android:exported="true"`) hosting the Gemma 4 4BE LLM
- **AIDL IPC** — any app can bind to `ILlmService` to submit and cancel requests
- **Request queue** — requests are processed FIFO; any queued request can be cancelled
- **RxKotlin streaming** — responses arrive as an `Observable<LlmState>` with three states:
  1. `Waiting` — request is in queue (includes remaining queue count)
  2. `Processing` — LLM is generating (streams partial text)
  3. `Completed` — generation finished (full response text)
- **Chat UI** — built-in chat screen using the service locally

## Architecture

```
app/
├── aidl/com/example/gemma4service/
│   ├── ILlmService.aidl        ← submit / cancel / queueSize
│   └── ILlmCallback.aidl       ← oneway streaming callbacks
├── inference/
│   └── GemmaInferenceEngine.kt ← MediaPipe LlmInference wrapper → Observable<String>
├── service/
│   ├── LlmQueueManager.kt      ← FIFO queue + BehaviorSubject<LlmState>
│   └── LlmForegroundService.kt ← AIDL Stub + foreground notification
├── model/
│   ├── LlmRequest.kt
│   └── LlmState.kt             ← sealed class: Waiting / Processing / Completed / Error
└── ui/
    ├── MainActivity.kt
    ├── ChatViewModel.kt         ← PublishSubject<LlmState> → LiveData
    ├── ChatAdapter.kt
    └── ChatMessage.kt
```

## Model Setup

The app uses the MediaPipe LLM Inference API. Before running:

1. Download the **Gemma 4 4BE** model (`.task` format) from [Google AI Edge Model Explorer](https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference/android).
2. Push the model file to the device:
   ```bash
   adb push gemma4-4be.task /data/data/com.example.gemma4service/files/gemma4-4be.task
   ```

## Using from Another App

Add the AIDL files to your client app and bind to the service:

```kotlin
val intent = Intent("com.example.gemma4service.ILlmService").apply {
    setPackage("com.example.gemma4service")
}
bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

// In onServiceConnected:
val llmService = ILlmService.Stub.asInterface(binder)
val requestId = UUID.randomUUID().toString()
llmService.submitRequest(requestId, "Your prompt here", object : ILlmCallback.Stub() {
    override fun onWaiting(requestId: String, queuePosition: Int) { /* ... */ }
    override fun onProcessing(requestId: String, partialText: String) { /* ... */ }
    override fun onCompleted(requestId: String, fullText: String) { /* ... */ }
    override fun onError(requestId: String, reason: String) { /* ... */ }
})

// To cancel:
llmService.cancelRequest(requestId)
```

## Building

```bash
./gradlew assembleDebug
```

Requires Android Studio Ladybug or later (AGP 8.7+).
