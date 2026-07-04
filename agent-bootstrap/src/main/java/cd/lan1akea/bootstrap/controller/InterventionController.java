package cd.lan1akea.bootstrap.controller;

import cd.lan1akea.core.intervention.InterventionRequest;
import cd.lan1akea.core.intervention.InterventionStore;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/interventions")
public class InterventionController {

    private final InterventionStore interventionStore;

    public InterventionController(InterventionStore interventionStore) {
        this.interventionStore = interventionStore;
    }

    @GetMapping("/pending")
    public List<InterventionRequest> pending() {
        return interventionStore.getAllPending();
    }

    @GetMapping("/pending/{sessionId}")
    public List<InterventionRequest> pendingBySession(@PathVariable String sessionId) {
        return interventionStore.getPendingBySession(sessionId);
    }

    @GetMapping("/all")
    public List<InterventionRequest> all() {
        return interventionStore.getAll();
    }

    @GetMapping("/{id}")
    public InterventionRequest getById(@PathVariable String id) {
        InterventionRequest req = interventionStore.getById(id);
        if (req == null) throw new RuntimeException("Intervention not found: " + id);
        return req;
    }

    @PostMapping("/{id}/resolve")
    public Map<String, Object> resolve(@PathVariable String id, @RequestBody Map<String, Object> body) {
        String action = (String) body.get("action");
        String comment = (String) body.getOrDefault("comment", "");
        String resolver = (String) body.getOrDefault("resolver", "api");

        InterventionRequest req = interventionStore.getById(id);
        if (req == null) throw new RuntimeException("Intervention not found: " + id);

        switch (action.toLowerCase()) {
            case "approve":
                interventionStore.approve(id, resolver, comment);
                break;
            case "deny":
                interventionStore.deny(id, resolver, comment);
                break;
            case "clarify":
                @SuppressWarnings("unchecked")
                Map<String, Object> modifiedArgs = (Map<String, Object>) body.get("modifiedArgs");
                interventionStore.clarify(id, resolver, comment, modifiedArgs);
                break;
            case "reply":
                interventionStore.approve(id, resolver, comment);
                break;
            default:
                throw new RuntimeException("Unknown action: " + action);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("interventionId", id);
        result.put("action", action);
        return result;
    }
}
