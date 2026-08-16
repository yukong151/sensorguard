# Release 签名与密钥管理

本文件说明 SensorGuard 社区版 release APK 的签名方式、密钥备份与轮换流程。**密钥文件与密码属于最高敏感资产,绝不入库、绝不上传。**

## 现状

- keystore 文件:`keystore/sensorguard-release.jks`(RSA-2048,有效期 10000 天,CN=SensorGuard / O=yukong151)
- 凭据文件:`keystore/creds.json`(storePassword / keyAlias / keyPassword)
- 上述目录已被 `.gitignore` 的 `keystore/` 排除,git 永不跟踪
- 签名证书 SHA-256:`a7c83b1d7b925e2836e758fc91ba4fdbf9506827415626c43b13f698d034ff7d`(2026-08-17 轮换后的正式签名)

## 备份(必须)

`keystore/` 目录一旦丢失:
- 已安装的用户将**无法升级**(签名不匹配)
- 只能作废重建并引导用户卸载重装

**请将 `keystore/sensorguard-release.jks` + `keystore/creds.json` 备份到安全位置**(密码管理器、离线加密存储等),至少两份异地。

## 构建正式签名 APK

```powershell
# 设置四个环境变量后执行
$env:SG_KEYSTORE_FILE    = "F:\KTV\keystore\sensorguard-release.jks"
$env:SG_KEYSTORE_PASSWORD = <storePassword>
$env:SG_KEY_ALIAS        = "sensorguard"
$env:SG_KEY_PASSWORD     = <keyPassword>

./gradlew :app:assembleInternalRelease
```

- 未设置或文件不存在时,`build.gradle.kts` 自动回退 debug 签名(仅本地开发,禁止用于发布)
- 产物:`app/build/outputs/apk/internal/release/app-internal-release.apk`

## 校验签名

```powershell
& "<SDK>\build-tools\35.0.0\apksigner.bat" verify --print-certs "app\build\outputs\apk\internal\release\app-internal-release.apk"
# 期望 Signer #1 DN: CN=SensorGuard, OU=Community, O=yukong151
```

## CI 注入

CI 通过 secrets 注入同名四个环境变量(`SG_KEYSTORE_FILE` / `SG_KEYSTORE_PASSWORD` / `SG_KEY_ALIAS` / `SG_KEY_PASSWORD`),将 keystore 文件经 secret 上传,即可产出正式签名构建。**不要把 keystore 作为构建产物或仓库文件暴露。**

## 密钥轮换流程(密钥泄露时)

1. 删除旧 keystore 与凭据:`Remove-Item keystore\sensorguard-release.jks, keystore\creds.json`
2. 生成新 keystore(参考下节命令)
3. 重新构建正式签名 release APK
4. 更新 GitHub Release 资产:删除旧 APK 资产 → 上传新 APK
5. 公告说明:旧版本无法升级,需卸载重装(签名变化)
6. 更新本文件中的 SHA-256 指纹

## 生成新 keystore

```powershell
$keytool = "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe"
$storePass = <强随机密码>
& $keytool -genkeypair -v -keystore "keystore\sensorguard-release.jks" `
    -alias sensorguard -keyalg RSA -keysize 2048 -validity 10000 `
    -storepass $storePass -keypass $storePass `
    -dname "CN=SensorGuard, OU=Community, O=yukong151, L=Unknown, ST=Unknown, C=CN"
```

生成后将 storePassword/keyAlias/keyPassword 写入 `keystore/creds.json`。

## 安全注意

- 密钥密码**绝不**出现在对话、issue、PR、README、日志中
- `keystore/` 已在 `.gitignore`,手动 `git add keystore/` 会被忽略;如改用其他路径,务必同步忽略
- GitHub/Gitee 仓库均为公开可见,任何入库的密钥等同于泄露
- 涉及安全披露一律走私有渠道(见 CONTRIBUTING),48h 响应