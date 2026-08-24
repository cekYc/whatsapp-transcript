# Üçüncü taraf bildirimleri

SesYazı aşağıdaki açık kaynak bileşenleri ve model dosyalarını kullanır:

- **sherpa-onnx** — k2-fsa, Apache License 2.0  
  Kaynak: https://github.com/k2-fsa/sherpa-onnx
- **Whisper** — OpenAI, MIT License  
  Kaynak: https://github.com/openai/whisper
- **sherpa-onnx Whisper Tiny, Base ve Small model dönüşümleri** —
  k2-fsa/csukuangfj
  Modeller: https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny,
  https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base,
  https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small
- **Silero VAD** — Silero Team, MIT License
  Kaynak: https://github.com/snakers4/silero-vad

AndroidX, Material Components ve Kotlin Coroutines kendi ilgili açık kaynak
lisanslarıyla dağıtılır. Uygulama model ağırlıklarını APK içine gömmez; ilk
kullanımda belirtilen model deposundan indirir.
