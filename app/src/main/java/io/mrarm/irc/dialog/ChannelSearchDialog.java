package io.mrarm.irc.dialog;

import android.content.Context;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.Pair;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.mrarm.irc.R;
import io.mrarm.irc.connection.ServerConnectionManager;
import io.mrarm.irc.connection.ServerConnectionSession;
import io.mrarm.irc.util.ClickableRecyclerViewAdapter;
import io.mrarm.irc.util.StyledAttributesHelper;

public class ChannelSearchDialog extends SearchDialog {

    private SuggestionsAdapter mAdapter;

    public ChannelSearchDialog(@NonNull Context context, ChannelSelectedListener listener) {
        super(context);

        getSearchView().setBackgroundColor(StyledAttributesHelper.getColor(context, R.attr.colorBackgroundFloating, 0));

        mAdapter = new SuggestionsAdapter(context);
        mAdapter.setItemClickListener((int index, Pair<ServerConnectionSession, String> value) ->{
            if (listener != null)
                listener.onChannelSelected(value.first, value.second);
            dismiss();
        });
        setSuggestionsAdapter(mAdapter);
    }

    public void onQueryTextChange(String newText) {
        mAdapter.filterWithQuery(newText);
    }

    public static class SuggestionsAdapter extends ClickableRecyclerViewAdapter<SuggestionsAdapter.SuggestionHolder, Pair<ServerConnectionSession, String>> {

        private final ServerConnectionManager mConnectionManager;
        private final int mSecondaryTextColor;
        private final int mHighlightTextColor;
        private String mHighlightQuery;

        public SuggestionsAdapter(Context context) {
            setViewHolderFactory(SuggestionHolder::new, R.layout.dialog_search_item);
            mConnectionManager = ServerConnectionManager.getInstance(context);
            mSecondaryTextColor = StyledAttributesHelper.getColor(context,
                    android.R.attr.textColorSecondary, 0);
            mHighlightTextColor = context.getResources().getColor(R.color.searchColorHighlight);
            filterWithQuery("");
        }

        public void filterWithQuery(String query) {
            List<Pair<ServerConnectionSession, String>> ret = new ArrayList<>();
            for (ServerConnectionSession info : mConnectionManager.getConnections()) {
                for (String channel : info.getChannels()) {
                    int iof = channel.indexOf(query);
                    if (iof != -1)
                        ret.add(new Pair<>(info, channel));
                }
            }
            Collections.sort(ret, (Pair<ServerConnectionSession, String> l,
                                   Pair<ServerConnectionSession, String> r) ->
                    Integer.compare(l.second.indexOf(query), r.second.indexOf(query)));
            mHighlightQuery = query;
            setItems(ret);
        }

        public class SuggestionHolder extends ClickableRecyclerViewAdapter.ViewHolder<Pair<ServerConnectionSession, String>> {
            private TextView mText;

            public SuggestionHolder(View itemView) {
                super(itemView);
                mText = itemView.findViewById(R.id.text);
            }

            @Override
            public void bind(Pair<ServerConnectionSession, String> item) {
                String name = item.first.getName();
                // Match position against the canonical channel string (same one filterWithQuery()
                // matched against), not the display string - casing may differ between the two,
                // which would make indexOf() miss the match against the display string.
                int iof = item.second.indexOf(mHighlightQuery);
                String channel = item.first.getChannelDisplayName(item.second);
                SpannableString str = new SpannableString(channel + "  " + name);
                str.setSpan(new ForegroundColorSpan(mHighlightTextColor), iof, iof + mHighlightQuery.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                str.setSpan(new ForegroundColorSpan(mSecondaryTextColor), channel.length() + 2, channel.length() + 2 + name.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                mText.setText(str);
            }
        }

    }

    public interface ChannelSelectedListener {

        void onChannelSelected(ServerConnectionSession server, String channel);

    }

}
