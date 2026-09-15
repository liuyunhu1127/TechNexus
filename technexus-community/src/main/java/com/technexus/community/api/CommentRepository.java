package com.technexus.community.api;

import com.technexus.community.domain.Comment;

public interface CommentRepository {
	void add(Comment comment);
}
