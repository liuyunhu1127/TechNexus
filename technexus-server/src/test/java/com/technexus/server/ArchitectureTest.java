package com.technexus.server;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.ArchConfiguration;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class ArchitectureTest {
	@Test
	void domainDoesNotDependOnFrameworkOrPersistencePackages() {
		ArchConfiguration.get().setResolveMissingDependenciesFromClassPath(false);
		var classes = new ClassFileImporter().importPackages("com.technexus");
		noClasses().that().resideInAPackage("..domain..").should().dependOnClassesThat()
				.resideInAnyPackage("org.springframework..", "jakarta.persistence..", "jakarta.servlet..",
						"com.fasterxml.jackson..")
				.check(classes);
		noClasses().that().resideInAPackage("..web..").should().dependOnClassesThat()
				.resideInAnyPackage("..infrastructure..").check(classes);
	}
}
