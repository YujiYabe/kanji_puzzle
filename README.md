# 漢字くみたて

漢字の部品を組み合わせながら、読み方や字形を学べる小学生向けの Android アプリです。

問題文に合う部品を選んで完成エリアへ配置し、正しい漢字を作ります。答え合わせには ML Kit の日本語文字認識を利用しています。

## 主な機能

- 小学校1〜6年生の学年別出題
- 5〜100問から問題数を選択
- 漢字の部品をドラッグ＆ドロップして自由に配置
- 日本語OCRと部品配置による正誤判定
- 正解1問につき10点のスコア表示
- 同じ漢字や問題に偏りにくい出題履歴
- スマートフォンと横向きタブレットに対応したレイアウト

## 遊び方

1. 学年と問題数を選び、「スタート」をタップします。
2. 問題文の赤い読みを手掛かりに、候補の部品を完成エリアへドラッグします。
3. 部品の位置を調整し、「こたえあわせ」をタップします。
4. 正解したら、完成した漢字と部品構成を確認して次の問題へ進みます。

配置した部品は「ひとつもどす」で取り消せます。

## 動作・開発環境

- Android 6.0（API 23）以上
- Android Studio（最新版を推奨）
- JDK 17
- Android SDK 35

主な使用技術は Kotlin、Jetpack Compose、Material 3、ML Kit Japanese Text Recognition です。

## セットアップ

1. このリポジトリをクローンします。
2. Android Studio でリポジトリのルートディレクトリを開きます。
3. Gradle の同期が完了したら、端末またはエミュレーターを選択します。
4. `app` 構成を実行します。

初回ビルド時は、Gradle と依存ライブラリを取得するためインターネット接続が必要です。OCRモデルはアプリに同梱されるため、ゲーム中の文字認識に通信権限は必要ありません。

### コマンドラインからビルドする

macOS / Linux:

```bash
./gradlew assembleDebug
```

Windows:

```powershell
.\gradlew.bat assembleDebug
```

生成されるデバッグAPKは `app/build/outputs/apk/debug/app-debug.apk` です。`make` が利用できる環境では、次のコマンドも使えます。

```bash
make debug    # デバッグAPKを作成
make install  # 接続中の端末へインストール（adbが必要）
make apk      # dist/kanji-kumitate.apk としてコピー
make clean    # ビルド成果物を削除
```

## 問題データ

元となる例文データは [`app/src/main/assets/kanji_yomi_questions.json`](app/src/main/assets/kanji_yomi_questions.json)、漢字の部品構成は [`app/src/main/assets/kanji_ids.txt`](app/src/main/assets/kanji_ids.txt) にあります。

例文データの主なフィールドは次のとおりです。

| フィールド | 内容 |
| --- | --- |
| `grade` | 学年（1〜6） |
| `target` | 出題する漢字 |
| `reading` | 問題文で示す読み |
| `sentence` | 例文。対象漢字を `[]`、読みを隠す箇所を `{}` で表現 |
| `sentenceReading` | 例文の読み |
| `english` / `spanish` | 例文の翻訳（現在の画面では未使用） |
| `vaild` | `false` の問題を出題対象から除外（データ仕様上の綴り） |

アプリは有効な例文とIDSデータから、部品・ダミー候補・配置方向を含む問題を生成します。生成済みデータはアプリ専用の外部ストレージに `kanji_questions.json` として保存され、元データが変わると自動的に再生成されます。一般的な保存先は次のとおりです。

```text
/storage/emulated/0/Android/data/com.example.kanjikumitate/files/kanji_questions.json
```

保存先は端末やAndroidのバージョンによって異なる場合があります。ファイルを削除した場合も、次回起動時に再生成されます。

### 出題する漢字を絞り込む（任意）

`app/src/main/assets/question_targets.json` を追加すると、学年ごとに出題対象を指定できます。このファイルがない場合は、生成可能なすべての漢字が対象です。

```json
{
  "schemaVersion": 1,
  "grades": {
    "1": { "include": [], "exclude": [] },
    "2": { "include": ["休", "時"], "exclude": [] }
  }
}
```

- `include`: 出題する漢字。空配列の場合はその学年の全候補を使用します。
- `exclude`: 出題しない漢字。`include` より優先されます。

## ライセンス

このプロジェクトは [MIT License](LICENSE) の下で公開されています。

漢字構成データには Yi Bai 氏の IDS データを利用しています。詳細は [`app/src/main/assets/licenses/yi-bai-ids-LICENSE.txt`](app/src/main/assets/licenses/yi-bai-ids-LICENSE.txt) を参照してください。
