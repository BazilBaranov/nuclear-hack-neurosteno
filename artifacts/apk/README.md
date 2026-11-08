# APK для установки

[Скачать neurosteno-arm64.apk](https://github.com/BazilBaranov/nuclear-hack-neurosteno/releases/latest/download/neurosteno-arm64.apk)

APK хранится в **Releases**, отдельно от исходников. Здесь сохранены описание
сборки и контрольная сумма. Скачайте APK и
[SHA256SUMS.txt](https://github.com/BazilBaranov/nuclear-hack-neurosteno/releases/latest/download/SHA256SUMS.txt)
в одну папку перед выполнением команд ниже.

- Android 9+ / API 28, ARM64 (`arm64-v8a`).
- Пакет `dev.neurostenografist.ultra.v2`, версия `0.3.0-ultra-optimized`.
- В APK включены Whisper small и Pyannote/TitaNet. Дополнительные модели доступны в приложении.
- APK подписан тем же debug-ключом, что и v0.2.0.

```bash
sha256sum -c SHA256SUMS.txt
adb -s SERIAL install --user 0 -r neurosteno-arm64.apk
```

[Установка, модели и использование](../../docs/INSTALLATION.md).
