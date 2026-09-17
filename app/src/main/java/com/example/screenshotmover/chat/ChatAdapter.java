package com.example.screenshotmover.chat;

import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.screenshotmover.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;

import java.util.List;

/** Message list for the chat screen. */
public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.Holder> {

    public interface Listener {
        void onSaveCandidate(ChatMessage message);
    }

    private final List<ChatMessage> items;
    private final Listener listener;

    public ChatAdapter(List<ChatMessage> items, Listener listener) {
        this.items = items;
        this.listener = listener;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_chat_msg, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        ChatMessage m = items.get(position);
        boolean user = ChatMessage.ROLE_USER.equals(m.role);
        boolean tool = ChatMessage.ROLE_TOOL.equals(m.role);

        h.role.setText(user ? "you" : tool
                ? ("tool: " + (m.toolName == null ? "" : m.toolName)) : "assistant");
        h.text.setText(m.text == null ? "" : m.text);
        h.text.setTextSize(tool ? 12f : 14f);
        h.text.setAlpha(tool ? 0.85f : 1f);

        boolean hasScript = m.pendingScript != null && !m.pendingScript.isEmpty();
        h.save.setVisibility(hasScript ? View.VISIBLE : View.GONE);
        h.save.setOnClickListener(hasScript ? v -> listener.onSaveCandidate(m) : null);

        // A turn that only called tools has no text; the tool result below already shows it.
        boolean empty = (m.text == null || m.text.isEmpty()) && !hasScript;
        h.itemView.setVisibility(empty ? View.GONE : View.VISIBLE);

        int bg;
        if (user) {
            bg = MaterialColors.getColor(h.card, com.google.android.material.R.attr.colorPrimaryContainer);
        } else if (tool) {
            bg = MaterialColors.getColor(h.card, com.google.android.material.R.attr.colorSurfaceVariant);
        } else {
            bg = MaterialColors.getColor(h.card, com.google.android.material.R.attr.colorSurfaceContainerHigh);
        }
        h.card.setCardBackgroundColor(bg);

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) h.card.getLayoutParams();
        lp.gravity = user ? Gravity.END : Gravity.START;
        h.card.setLayoutParams(lp);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final MaterialCardView card;
        final TextView role, text;
        final MaterialButton save;

        Holder(@NonNull View v) {
            super(v);
            card = v.findViewById(R.id.msg_card);
            role = v.findViewById(R.id.tv_msg_role);
            text = v.findViewById(R.id.tv_msg_text);
            save = v.findViewById(R.id.btn_msg_save);
        }
    }
}
