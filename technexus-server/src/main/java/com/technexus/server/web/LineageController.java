package com.technexus.server.web;

import com.technexus.server.demand.application.DemandApplicationService;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/lineages")
public class LineageController {
	private final DemandApplicationService demands;

	public LineageController(DemandApplicationService demands) {
		this.demands = demands;
	}

	@GetMapping("/{targetType}/{targetId}")
	DemandApplicationService.LineageView getLineage(
			@PathVariable @Pattern(regexp = "CONTENT|DEMAND|PROPOSAL") String targetType, @PathVariable UUID targetId) {
		return demands.getLineage(targetType, targetId);
	}
}
