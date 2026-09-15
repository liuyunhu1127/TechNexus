package com.technexus.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class DatabaseNamingTest {
	private static final Pattern CREATE_TABLE = Pattern.compile("(?im)^CREATE TABLE (tn_[a-z0-9_]+) ");

	@Test
	void migrationUsesOnlyTnSnakeCaseAndContainsImageBaselineTables() throws IOException {
		var migrationDirectory = Path.of("src", "main", "resources", "db", "migration");
		var migrations = Files.list(migrationDirectory)
				.filter(path -> path.getFileName().toString().matches("V\\d+__[a-z0-9_]+\\.sql"))
				.sorted(java.util.Comparator.comparingInt(DatabaseNamingTest::migrationVersion)).toList();
		var sql = migrations.stream().map(path -> {
			try {
				return Files.readString(path);
			} catch (IOException error) {
				throw new java.io.UncheckedIOException(error);
			}
		}).collect(Collectors.joining("\n"));
		var tables = CREATE_TABLE.matcher(sql).results().map(result -> result.group(1)).collect(Collectors.toSet());
		var core = Set.of("tn_user", "tn_role", "tn_permission", "tn_article", "tn_post", "tn_comment", "tn_demand",
				"tn_audit_task", "tn_price_object", "tn_file_object");
		assertTrue(tables.containsAll(core));
		assertEquals(tables.size(), CREATE_TABLE.matcher(sql).results().count());
		assertTrue(tables.stream().allMatch(name -> name.matches("tn_[a-z0-9]+(?:_[a-z0-9]+)*")));

		assertEquals(11, migrations.size());
		for (int index = 0; index < migrations.size(); index++) {
			assertTrue(migrations.get(index).getFileName().toString().startsWith("V" + (index + 1) + "__"));
		}
	}

	private static int migrationVersion(Path path) {
		var name = path.getFileName().toString();
		return Integer.parseInt(name.substring(1, name.indexOf("__")));
	}
}
