package com.example.screenshotmover.chat;

/** Tiny line diff for the script confirmation dialog. */
final class DiffUtil {
    private DiffUtil() {}

    static String diff(String oldText, String newText) {
        String[] a = (oldText == null ? "" : oldText).split("\n", -1);
        String[] b = (newText == null ? "" : newText).split("\n", -1);
        if (a.length > 400 || b.length > 400) return "(too large to diff)";
        int n = a.length;
        int m = b.length;
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                dp[i][j] = a[i].equals(b[j])
                        ? dp[i + 1][j + 1] + 1
                        : Math.max(dp[i + 1][j], dp[i][j + 1]);
            }
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (a[i].equals(b[j])) {
                sb.append("  ").append(a[i]).append('\n');
                i++;
                j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                sb.append("- ").append(a[i]).append('\n');
                i++;
            } else {
                sb.append("+ ").append(b[j]).append('\n');
                j++;
            }
        }
        while (i < n) sb.append("- ").append(a[i++]).append('\n');
        while (j < m) sb.append("+ ").append(b[j++]).append('\n');
        return sb.toString();
    }
}
