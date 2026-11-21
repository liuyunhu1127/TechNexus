package com.technexus.server.user.application;

import com.technexus.common.domain.DomainException;
import com.technexus.common.domain.UuidV7;
import com.technexus.server.file.application.port.FileRecordPort;
import com.technexus.server.file.application.port.ObjectStoragePort;
import com.technexus.user.api.UserRepository;
import com.technexus.user.domain.User;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class AvatarApplicationService {
	private static final int MAX_BYTES = 5 * 1024 * 1024;
	private static final int MAX_EDGE = 4096;
	private static final int OUTPUT_EDGE = 512;
	private final UserRepository users;
	private final FileRecordPort files;
	private final ObjectStoragePort storage;

	public AvatarApplicationService(UserRepository users, FileRecordPort files, ObjectStoragePort storage) {
		this.users = users;
		this.files = files;
		this.storage = storage;
	}

	@Transactional(readOnly = true)
	public AvatarView get(UUID actor) {
		var user = requireUser(actor);
		if (user.avatarObjectKey() == null)
			return new AvatarView(null, null, user.version());
		var signed = storage.presignDownload(user.avatarObjectKey());
		return new AvatarView(signed.url(), signed.expiresAt(), user.version());
	}

	@Transactional
	public long change(UUID actor, UUID fileId, long expectedVersion) {
		var user = requireUser(actor);
		if (user.version() != expectedVersion)
			throw new DomainException("VERSION_CONFLICT", "用户资料版本已变化");
		var previous = user.avatarObjectKey();
		String next = fileId == null ? null : store(actor, fileId);
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCompletion(int status) {
				if (status == STATUS_COMMITTED)
					delete(previous);
				if (status == STATUS_ROLLED_BACK)
					delete(next);
			}
		});
		user.changeAvatar(next);
		users.save(user);
		return user.version();
	}

	private String store(UUID actor, UUID fileId) {
		var file = files.requireOwned(fileId, actor);
		if (!"AVAILABLE".equals(file.state())
				|| !("image/png".equals(file.detectedMime()) || "image/jpeg".equals(file.detectedMime())))
			throw invalid();
		var source = files.requireDownloadable(fileId, actor);
		var key = "avatars/" + actor + "/" + UuidV7.generate() + ".png";
		byte[] png;
		try (var content = storage.openContent(source.objectKey())) {
			byte[] input = content.readNBytes(MAX_BYTES + 1);
			if (input.length > MAX_BYTES)
				throw invalid();
			png = reencode(input);
		} catch (IOException malformed) {
			throw invalid();
		}
		storage.storeAvatar(key, png);
		return key;
	}

	private static byte[] reencode(byte[] bytes) throws IOException {
		try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
			var readers = ImageIO.getImageReaders(input);
			if (!readers.hasNext())
				throw invalid();
			var reader = readers.next();
			try {
				if (!"png".equalsIgnoreCase(reader.getFormatName()) && !"jpeg".equalsIgnoreCase(reader.getFormatName()))
					throw invalid();
				reader.setInput(input, true, true);
				int width = reader.getWidth(0);
				int height = reader.getHeight(0);
				if (width < 1 || height < 1 || width > MAX_EDGE || height > MAX_EDGE)
					throw invalid();
				int edge = Math.min(width, height);
				var image = reader.read(0);
				var output = new BufferedImage(OUTPUT_EDGE, OUTPUT_EDGE, BufferedImage.TYPE_INT_ARGB);
				var graphics = output.createGraphics();
				try {
					graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
							RenderingHints.VALUE_INTERPOLATION_BILINEAR);
					int x = (width - edge) / 2;
					int y = (height - edge) / 2;
					graphics.drawImage(image, 0, 0, OUTPUT_EDGE, OUTPUT_EDGE, x, y, x + edge, y + edge, null);
				} finally {
					graphics.dispose();
				}
				var png = new ByteArrayOutputStream();
				ImageIO.write(output, "png", png);
				return png.toByteArray();
			} finally {
				reader.dispose();
			}
		}
	}

	private User requireUser(UUID actor) {
		return users.findById(actor).orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND", "用户不存在"));
	}

	private void delete(String key) {
		if (key == null)
			return;
		try {
			storage.delete(key);
		} catch (RuntimeException failure) {
			LoggerFactory.getLogger(getClass()).warn("Failed to clean up an unused avatar");
		}
	}

	private static DomainException invalid() {
		return new DomainException("AVATAR_INVALID", "请选择已通过扫描的 JPEG 或 PNG 图片，大小不超过 5 MiB，宽高不超过 4096 像素");
	}

	public record AvatarView(URI url, Instant expiresAt, long version) {
	}
}
