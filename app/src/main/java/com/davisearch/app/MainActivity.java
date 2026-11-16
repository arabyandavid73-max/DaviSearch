package com.davisearch.app;

import android.app.*;
import android.os.*;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends AppCompatActivity {

    private EditText searchInput;
    private TextView resultsText, folderStatus;
    private Button searchButton, selectFolderButton;

    private final List<Uri> folders = new ArrayList<>();
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();

    private static final int PICK_FOLDER = 100;
    private static final int MAX_RESULTS = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        searchInput = findViewById(R.id.searchInput);
        resultsText = findViewById(R.id.resultsText);
        folderStatus = findViewById(R.id.folderStatus);
        searchButton = findViewById(R.id.searchButton);
        selectFolderButton = findViewById(R.id.selectFolderButton);

        searchButton.setOnClickListener(v -> startSearch());

        selectFolderButton.setOnClickListener(v -> selectFolder());

    }

    private void selectFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        startActivityForResult(intent, PICK_FOLDER);
    }

    @Override
    protected void onActivityResult(
            int requestCode, int resultCode, Intent data) {

        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == PICK_FOLDER &&
                resultCode == RESULT_OK && data != null) {

            Uri uri = data.getData();

            try {
                getContentResolver().takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                );

                if (!folders.contains(uri)) {
                    folders.add(uri);
                }

                folderStatus.setText(
                        "Выбрано папок: " + folders.size()
                );

            } catch (Exception e) {
                folderStatus.setText(
                        "Не удалось сохранить доступ: " + e.getMessage()
                );
            }
        }
    }

    private void startSearch() {

        String query = searchInput.getText()
                .toString().trim().toLowerCase(Locale.ROOT);

        if (query.length() < 2) {
            Toast.makeText(this,
                    "Введите минимум 2 символа",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        if (folders.isEmpty()) {
            Toast.makeText(this,
                    "Сначала выберите папку",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        searchButton.setEnabled(false);
        resultsText.setText("Поиск выполняется...");

        executor.execute(() -> {

            StringBuilder output = new StringBuilder();

            int filesScanned = 0;
            int matches = 0;

            Set<String> visited = new HashSet<>();

            for (Uri folder : folders) {

                int[] stats = scanFolder(
                        folder,
                        query,
                        output,
                        visited,
                        0
                );

                filesScanned += stats[0];
                matches += stats[1];
            }

            final int totalFiles = filesScanned;
            final int totalMatches = matches;
            final String result = output.toString();

            runOnUiThread(() -> {

                searchButton.setEnabled(true);

                resultsText.setText(
                        "Поиск завершён\n\n" +
                        "Проверено файлов: " + totalFiles +
                        "\nСовпадений: " + totalMatches +
                        "\n\n" +
                        (result.isEmpty()
                                ? "Совпадений не найдено."
                                : result)
                );
            });
        });
    }

    private int[] scanFolder(
            Uri treeUri,
            String query,
            StringBuilder output,
            Set<String> visited,
            int depth) {

        int files = 0;
        int matches = 0;

        if (depth > 30 || output.length() > 20000) {
            return new int[]{0, 0};
        }

        String documentId;

        try {
            documentId =
                    DocumentsContract.getTreeDocumentId(treeUri);
        } catch (Exception e) {
            return new int[]{0, 0};
        }

        return scanDocument(
                treeUri,
                documentId,
                query,
                output,
                visited,
                depth
        );
    }

    private int[] scanDocument(
            Uri treeUri,
            String documentId,
            String query,
            StringBuilder output,
            Set<String> visited,
            int depth) {

        int files = 0;
        int matches = 0;

        if (depth > 30 || output.length() > 20000) {
            return new int[]{0, 0};
        }

        Uri childrenUri =
                DocumentsContract.buildChildDocumentsUriUsingTree(
                        treeUri, documentId
                );

        try (Cursor cursor = getContentResolver().query(
                childrenUri,
                new String[]{
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                },
                null,
                null,
                null
        )) {

            if (cursor == null) {
                return new int[]{0, 0};
            }

            while (cursor.moveToNext()) {

                if (output.length() > 20000) break;

                String id = cursor.getString(0);
                String name = cursor.getString(1);
                String mime = cursor.getString(2);

                Uri fileUri =
                        DocumentsContract.buildDocumentUriUsingTree(
                                treeUri, id
                        );

                if (DocumentsContract.Document.MIME_TYPE_DIR
                        .equals(mime)) {

                    int[] nested = scanDocument(
                            treeUri,
                            id,
                            query,
                            output,
                            visited,
                            depth + 1
                    );

                    files += nested[0];
                    matches += nested[1];

                } else {

                    String lower = name.toLowerCase(Locale.ROOT);

                    if (!lower.endsWith(".txt") &&
                            !lower.endsWith(".csv") &&
                            !lower.endsWith(".json")) {
                        continue;
                    }

                    if (!visited.add(fileUri.toString())) {
                        continue;
                    }

                    files++;

                    int found = searchFile(
                            fileUri,
                            name,
                            query,
                            output
                    );

                    matches += found;
                }
            }

        } catch (Exception e) {
            // Пропускаем недоступную папку.
        }

        return new int[]{files, matches};
    }

    private int searchFile(
            Uri uri,
            String name,
            String query,
            StringBuilder output) {

        int found = 0;

        try (InputStream input =
                     getContentResolver().openInputStream(uri)) {

            if (input == null) return 0;

            InputStreamReader reader =
                    new InputStreamReader(input, "UTF-8");

            char[] buffer = new char[8192];

            StringBuilder tail = new StringBuilder();

            int length;
            String normalizedQuery =
                    query.toLowerCase(Locale.ROOT);

            while ((length = reader.read(buffer)) != -1) {

                String chunk = new String(buffer, 0, length);

                String combined = (
                        tail.toString() + chunk
                ).toLowerCase(Locale.ROOT);

                int position = 0;

                while ((position =
                        combined.indexOf(normalizedQuery, position))
                        != -1) {

                    found++;

                    if (found == 1) {
                        output.append("📄 ")
                                .append(name)
                                .append("\n");
                    }

                    position += normalizedQuery.length();
                }

                int keep = Math.min(
                        normalizedQuery.length() - 1,
                        combined.length()
                );

                tail.setLength(0);

                if (keep > 0) {
                    tail.append(
                            combined.substring(
                                    combined.length() - keep
                            )
                    );
                }

            }

            if (found > 0) {
                output.append("Совпадений: ")
                        .append(found)
                        .append("\n\n");
            }

        } catch (Exception e) {
            // Ошибка чтения отдельного файла.
        }

        return found;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
