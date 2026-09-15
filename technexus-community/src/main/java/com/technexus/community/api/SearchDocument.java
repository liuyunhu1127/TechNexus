package com.technexus.community.api;

import com.technexus.common.domain.TargetRef;
import com.technexus.common.domain.Visibility;
import java.time.Instant;

public record SearchDocument(TargetRef target, String title, String summary, Visibility visibility, Instant indexedAt) {
}
