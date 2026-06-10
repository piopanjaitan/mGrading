package com.ustp.mgrading.ui.detection;

import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.ustp.mgrading.data.local.GradingTag;
import com.ustp.mgrading.data.local.GradingTagRepository;
import com.ustp.mgrading.databinding.ActivitySavedTagsBinding;

import java.util.List;
import java.util.Locale;

public class SavedTagsActivity extends AppCompatActivity {
    private ActivitySavedTagsBinding binding;
    private GradingTagAdapter adapter;
    private GradingTagRepository repository;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySavedTagsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        repository = new GradingTagRepository(this);
        adapter = new GradingTagAdapter();
        binding.savedRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        binding.savedRecyclerView.setAdapter(adapter);

        binding.backButton.setOnClickListener(v -> finish());
        binding.refreshButton.setOnClickListener(v -> loadSavedTags());
        loadSavedTags();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadSavedTags();
    }

    private void loadSavedTags() {
        List<GradingTag> tags = repository.getAllSavedTags();
        adapter.submit(tags);
        binding.emptyText.setVisibility(tags.isEmpty() ? View.VISIBLE : View.GONE);
        binding.savedRecyclerView.setVisibility(tags.isEmpty() ? View.GONE : View.VISIBLE);
        binding.countText.setText(String.format(Locale.US, "Total %d data TBS", tags.size()));
        binding.classSummaryText.setText(toClassSummary(tags));
    }

    private String toClassSummary(List<GradingTag> tags) {
        int[] counts = new int[4];
        for (GradingTag tag : tags) {
            if (tag.getClassId() >= 0 && tag.getClassId() < counts.length) {
                counts[tag.getClassId()]++;
            }
        }
        return String.format(Locale.US,
                "Kurang Masak: %d, Masak: %d, Mentah: %d, Terlalu Masak: %d",
                counts[0], counts[1], counts[2], counts[3]);
    }
}
