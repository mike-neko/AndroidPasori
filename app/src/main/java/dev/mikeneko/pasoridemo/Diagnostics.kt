package dev.mikeneko.pasoridemo

/**
 * 読み取り成功の集計。画面上部の集計表示と「集計クリア」ボタンが使う。
 */
class Diagnostics {
    var successCount = 0
        private set

    fun clearStatistics() {
        successCount = 0
    }

    fun onReadSuccess() {
        successCount += 1
    }

    fun summary(): String {
        return "読み取り成功: $successCount 件"
    }
}
