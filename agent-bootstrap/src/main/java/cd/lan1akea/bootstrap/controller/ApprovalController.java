package cd.lan1akea.bootstrap.controller;

import cd.lan1akea.core.approval.ApprovalStore;
import cd.lan1akea.core.approval.PendingApproval;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/approvals")
public class ApprovalController {

    private final ApprovalStore approvalStore;

    public ApprovalController(ApprovalStore approvalStore) {
        this.approvalStore = approvalStore;
    }

    @GetMapping("/pending")
    public Mono<List<PendingApproval>> getAllPending() {
        return Mono.just(approvalStore.getAllPending());
    }

    @GetMapping("/all")
    public Mono<List<PendingApproval>> getAll() {
        return Mono.just(approvalStore.getAll());
    }

    @GetMapping
    public Mono<List<PendingApproval>> getBySession(
            @RequestParam("sessionId") String sessionId) {
        return Mono.just(approvalStore.getPendingBySession(sessionId));
    }

    @GetMapping("/{approvalId}")
    public Mono<PendingApproval> getById(@PathVariable String approvalId) {
        return Mono.justOrEmpty(approvalStore.getById(approvalId));
    }

    @PostMapping("/{approvalId}/approve")
    public Mono<Map<String, Object>> approve(@PathVariable String approvalId,
                                              @RequestBody Map<String, String> body) {
        String comment = body.getOrDefault("comment", "");
        String approver = body.getOrDefault("approver", "admin");
        approvalStore.approve(approvalId, approver, comment);
        return Mono.just(Map.of("ok", true, "message", "已批准"));
    }

    @PostMapping("/{approvalId}/deny")
    public Mono<Map<String, Object>> deny(@PathVariable String approvalId,
                                           @RequestBody Map<String, String> body) {
        String comment = body.getOrDefault("comment", "");
        String approver = body.getOrDefault("approver", "admin");
        approvalStore.deny(approvalId, approver, comment);
        return Mono.just(Map.of("ok", true, "message", "已拒绝"));
    }
}
