package io.mrarm.irc;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;

import java.io.FileInputStream;
import java.io.IOException;

import io.mrarm.irc.chat.ChatFragment;
import io.mrarm.irc.protocol.irc.dcc.DCCServer;
import io.mrarm.irc.protocol.irc.dcc.DCCUtils;

public class DCCCoordinator {
    public interface Host {
        Context getContext();
        ChatFragment getCurrentChat();

        ActivityResultLauncher<String> getFilePicker();

    }

    private final Host host;

    public DCCCoordinator(Host host) {
        this.host = host;
    }

    public void requestFileSend() {
        host.getFilePicker().launch("*/*");
    }

    public void openTransfers() {
        host.getContext().startActivity(
                new Intent(host.getContext(), DCCActivity.class)
        );
    }

    public void onFilePicked(Uri uri) {
        ChatFragment chat = host.getCurrentChat();
        if (chat == null) return;

        Context ctx = host.getContext();

        try (Cursor cursor = ctx.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) return;

            int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
            int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);

            String name = DCCUtils.escapeFilename(cursor.getString(nameIndex));
            long size = cursor.isNull(sizeIndex) ? -1 : cursor.getLong(sizeIndex);

            ParcelFileDescriptor desc = ctx.getContentResolver().openFileDescriptor(uri, "r");
            if (desc == null) throw new IOException();

            if (size == -1) size = desc.getStatSize();

            String channel = chat.getCurrentChannel();

            DCCServer.FileChannelFactory factory =
                    () -> new FileInputStream(desc.getFileDescriptor())
                            .getChannel().position(0);

            DCCManager.getInstance(ctx)
                    .startUpload(chat.getConnectionInfo(), channel, factory, name, size);

            // Do NOT close desc here unless you are sure startUpload consumes immediately.
            // If it doesn't, you need a different strategy (see note below).

        } catch (IOException e) {
            Toast.makeText(ctx, R.string.error_file_open, Toast.LENGTH_SHORT).show();
        }
    }
}
