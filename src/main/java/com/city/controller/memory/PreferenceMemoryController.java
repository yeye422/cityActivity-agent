package com.city.controller.memory;

import com.city.constants.CityConstants;
import com.city.model.PreferenceFact;
import com.city.model.PreferenceFactRequest;
import com.city.service.worker.MemoryWorker;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/city/preferences")
public class PreferenceMemoryController {
    private final MemoryWorker memoryWorker;

    public PreferenceMemoryController(MemoryWorker memoryWorker) {
        this.memoryWorker = memoryWorker;
    }

    @GetMapping
    public List<PreferenceFact> list(
            @RequestHeader(value = CityConstants.USER_ID, defaultValue = "1") Long userId) {
        return memoryWorker.findActive(userId);
    }

    @PostMapping
    public PreferenceFact remember(
            @RequestHeader(value = CityConstants.USER_ID, defaultValue = "1") Long userId,
            @RequestBody PreferenceFactRequest request) {
        return memoryWorker.remember(userId, request);
    }

    @DeleteMapping("/{id}")
    public void forget(
            @RequestHeader(value = CityConstants.USER_ID, defaultValue = "1") Long userId,
            @PathVariable Long id,
            @RequestParam Integer version) {
        memoryWorker.forget(userId, id, version);
    }
}
