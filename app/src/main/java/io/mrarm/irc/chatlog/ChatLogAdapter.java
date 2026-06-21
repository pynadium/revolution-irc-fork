package io.mrarm.irc.chatlog;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import io.mrarm.irc.R;
import io.mrarm.irc.storage.db.MessageEntity;

public class ChatLogAdapter extends RecyclerView.Adapter<ChatLogAdapter.Holder> {

    private final SimpleDateFormat mFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private final List<MessageEntity> mMessages = new ArrayList<>();

    public boolean hasMessages() {
        return !mMessages.isEmpty();
    }

    public long getOldestId() {
        return mMessages.get(0).id;
    }

    public void setMessages(List<MessageEntity> messages) {
        mMessages.clear();
        mMessages.addAll(messages);
        notifyDataSetChanged();
    }

    public void addToTop(List<MessageEntity> olderMessages) {
        if (olderMessages.isEmpty())
            return;
        mMessages.addAll(0, olderMessages);
        notifyItemRangeInserted(0, olderMessages.size());
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.chat_message, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        MessageEntity e = mMessages.get(position);
        String line = "[" + mFormat.format(new Date(e.timestamp)) + "] "
                + (e.sender != null ? e.sender + ": " : "")
                + (e.text != null ? e.text : "");
        holder.text.setText(line);
    }

    @Override
    public int getItemCount() {
        return mMessages.size();
    }

    public static class Holder extends RecyclerView.ViewHolder {
        final TextView text;

        public Holder(@NonNull View itemView) {
            super(itemView);
            text = itemView.findViewById(R.id.chat_message);
        }
    }
}
