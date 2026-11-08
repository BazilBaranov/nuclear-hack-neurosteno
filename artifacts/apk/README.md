# APK для установки

[Скачать neurosteno-arm64.apk](https://github.com/BazilBaranov/nuclear-hack-neurosteno/releases/latest/download/neurosteno-arm64.apk)

APK хранится в **Releases**, отдельно от исходников. Здесь сохранены описание
сборки и контрольная сумма. Скачайте APK и
[SHA256SUMS.txt](https://github.com/BazilBaranov/nuclear-hack-neurosteno/releases/latest/download/SHA256SUMS.txt)
в одну папку перед выполнением команд ниже.

- Android 9+ / API 28, ARM64 (`arm64-v8a`).
- Пакет `dev.neurostenografist.ultra`, версия `0.1.0-ultra-optimized`.
- 69 404 691 байт; веса загружаются отдельно в приложении.
- Код приложения: `6a445ae37c1723bdc9c97675eccae4e3d47d90e3`.
- APK подписан debug-ключом сборочной машины; `debuggable` и `testOnly` не включены.
- Установлен и запущен на Honor 200; SHA-256 установленного файла совпал.

```bash
sha256sum -c SHA256SUMS.txt
adb -s SERIAL install -r neurosteno-arm64.apk
```

[Установка, модели и использование](../../docs/INSTALLATION.md).
