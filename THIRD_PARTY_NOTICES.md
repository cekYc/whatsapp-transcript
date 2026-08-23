# Üçüncü taraf bildirimleri

SesYazı aşağıdaki açık kaynak bileşenleri ve model dosyalarını kullanır:

- **sherpa-onnx** — k2-fsa, Apache License 2.0  
  Kaynak: https://github.com/k2-fsa/sherpa-onnx
- **Whisper** — OpenAI, MIT License  
  Kaynak: https://github.com/openai/whisper
- **sherpa-onnx-whisper-tiny model dönüşümü** — k2-fsa/csukuangfj  
  Model: https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny

AndroidX, Material Components ve Kotlin Coroutines kendi ilgili açık kaynak
lisanslarıyla dağıtılır. Uygulama model ağırlıklarını APK içine gömmez; ilk
kullanımda belirtilen model deposundan indirir.
