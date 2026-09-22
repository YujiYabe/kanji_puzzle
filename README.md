# 漢字くみたて

小学生向けの Android 漢字学習アプリです。Kotlin / Jetpack Compose / Material 3 で、漢字の部品を組み合わせて正しい漢字を完成させる「漢字くみたて」を実装しています。

## 内容

- 1年生から6年生までの学年選択
- 漢字の意味、読み、ヒントを見て部品をドラッグする問題
- IDS（漢字構成記述）から取り出した部品をドラッグして出題
- 完成エリア内の自由な位置へ部品を置く「福笑い」形式
- 完成エリアを画像化し、ML Kit日本語OCRで正誤判定
- 正解時の完成漢字と部品構成の表示
- 点数表示

## 問題データ

問題文の元データは `app/src/main/assets/kanji_yomi_questions.json` に保存されています。`questions` の各要素は `grade`、`target`、`reading`、`sentence`、`sentenceReading`、`english`、`spanish`、`vaild` を持ちます。`vaild` は省略時も有効で、`false` にするとその問題を出題対象から除外します。

出題対象は `app/src/main/assets/question_targets.json` で学年別に管理します。

- `include`: 出題する漢字。空配列なら、その学年の全候補を使用します。
- `exclude`: 出題しない漢字。`include` より優先されます。

例えば2年生を「休」と「時」だけに限定する場合は、次のように指定します。

```json
"2": { "include": ["休", "時"], "exclude": [] }
```

設定ファイルを変更すると、保存済みの問題キャッシュは次回起動時に自動更新されます。

初回起動時に次の場所へJSON形式で問題データを書き出します。

```text
/storage/emulated/0/Android/data/com.example.kanjikumitate/files/kanji_questions.json
```

以後はこのJSONを起動時に読み込みます。`questions` 配列の各要素には、`target`、`reading`、`grade`、`promptBefore`、`targetKana`、`promptAfter`、`parts`、`distractors`、`hint`、`layout` を指定します。`layout` は左右配置の `Horizontal` または上下配置の `Vertical` です。

JSONが壊れていて読み込めない場合は、同梱した元データから問題を再構築します。JSONを削除すると、次回起動時に初期データを再生成します。

## 開発環境

- Android Studio
- JDK 17
- Android SDK 35
- minSdk 23

## 起動

Android Studio でこのディレクトリを開き、`app` を実行してください。

コマンドラインでは次を実行します。

```bash
./gradlew assembleDebug
```
