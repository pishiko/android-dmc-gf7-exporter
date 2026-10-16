# Panasonic LUMIX DMC-GF7 Image Exporter for Android

Panasonic LUMIX DMC-GF7 の Wi-Fi に接続し、カメラ内の未保存画像を Android 端末の `DCIM` ディレクトリへ一括エクスポートする Android アプリです。
パッケージID: `com.p4ko.dmcgf7exporter`

## 主な機能・仕様

1. **「1. 接続」→「2. 保存」→「3. 切断」の直感的な3ステップ**
   - **1. 接続**: `WifiNetworkSpecifier` によりカメラの Wi-Fi（デフォルト: `MYCAM` 前方一致）を自動検出・接続し、カメラ側へのアクセス要求（`req_acc`）と画面承認待ち（`getstate`）、再生モード切り替えまで自動で完了します。
   - **2. 保存**: カメラ内の全画像を走査し、端末の `DCIM` 内の既存ファイルと比較して、未保存の写真のみを自動ダウンロードします。
   - **3. 切断**: カメラ通信を終了し、スマホをカメラの Wi-Fi から切断して元の通常ネットワーク（自宅Wi-Fiやモバイル回線）へ復帰させます。
2. **バックグラウンド実行（フォアグラウンドサービス + WakeLock / WifiLock）**
   - 写真保存中にアプリを閉じたり画面が消灯しても、フォアグラウンドサービス（`CameraSyncService`）でダウンロードが中断されずに継続します。
3. **DLNA ContentDirectory `Browse` ページングによる全件走査**
   - ポート `60606` の `/Server0/CDS_control`（必要に応じて `/Server0/ddd` から自動検出）に対し、`BrowseDirectChildren` リクエストをページング送信し、SD カード内の全メディアを最後まで走査します。
4. **JPEG オリジナル優先 & RAW (RW2) / 動画 (MP4) 拡張対応構造**
   - DLNA `DIDL-Lite` の `<res protocolInfo="...">` を解析し、`PANASONIC.COM_PN=CAM_ORG`（通常 JPEG）および `PANASONIC.COM_PN=CAM_RAW_JPG`（RAW+JPEG 撮影時のオリジナル JPEG）を `MediaFormat.JPEG_ORIGINAL` として優先選択します。
   - `MediaFormat.RAW` (`PANASONIC.COM_PN=CAM_RAW` / `.RW2`) や `MediaFormat.VIDEO` (`PANASONIC.COM_PN=CAM_AVC_MP4` / `.MP4`) もモデルおよび `PriorityMediaSelectionPolicy` でサポートしており、`MediaSelectionConfig` の切り替えのみで対応可能です。
5. **ファイル名による保存済み判定と差分自動ダウンロード**
   - Android 端末側の保存先ディレクトリ（デフォルト: `DCIM/DMC-GF7`）内のファイル名とカメラ内画像を比較（DLNA 名 `DOxxxxxx.JPG` および LUMIX 標準 DCF 名 `Pxxxxxx.JPG` の両方に対応）し、未保存の画像のみをポート `50001` から自動ダウンロードします。
6. **設定のカスタマイズ**
   - カメラ Wi-Fi SSID（デフォルト `MYCAM` 前方一致）、保存先ディレクトリ（`DCIM` 配下の任意のパス）、カメラ IP アドレス（デフォルト `192.168.54.1`）を設定欄から変更・永続化可能。

## ビルドとテスト

```bash
./gradlew testDebugUnitTest assembleDebug
```
