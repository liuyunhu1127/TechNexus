package com.technexus.server.community.infrastructure;

import com.technexus.community.api.CommentRepository;
import com.technexus.community.domain.Comment;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("test")
public class InMemoryCommentRepository implements CommentRepository {
	private final List<Comment> values = new CopyOnWriteArrayList<>();
	@Override
	public void add(Comment comment) {
		values.add(comment);
	}
}
