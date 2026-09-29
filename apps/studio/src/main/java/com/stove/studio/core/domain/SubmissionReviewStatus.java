package com.stove.studio.core.domain;

import java.util.List;

public record SubmissionReviewStatus(Submission submission, List<SubmissionGate> gates) {
}
