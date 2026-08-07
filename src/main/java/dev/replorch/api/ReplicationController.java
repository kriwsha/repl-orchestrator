package dev.replorch.api;

import dev.replorch.domain.HealthReport;
import dev.replorch.domain.ReplicationSpec;
import dev.replorch.monitor.ReplicationMonitor;
import dev.replorch.provision.ReplicationProvisioner;
import dev.replorch.reconcile.ReconcileAction;
import dev.replorch.registry.ReplicationRegistry;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/replications")
public class ReplicationController {

    private final ReplicationProvisioner provisioner;
    private final ReplicationMonitor monitor;
    private final ReplicationRegistry registry;

    public ReplicationController(ReplicationProvisioner provisioner,
                                 ReplicationMonitor monitor,
                                 ReplicationRegistry registry) {
        this.provisioner = provisioner;
        this.monitor = monitor;
        this.registry = registry;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody ReplicationRequest req)
            throws SQLException {
        ReplicationSpec spec = req.toSpec();
        if (registry.get(spec.name()).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "replication '" + spec.name() + "' already exists"));
        }
        List<String> steps = provisioner.provision(spec);
        registry.put(spec);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("replication", spec.name(), "steps", steps));
    }

    @GetMapping
    public Collection<ReplicationSpec> list() { return registry.all(); }

    @GetMapping("/{name}/health")
    public ResponseEntity<?> health(@PathVariable String name) throws SQLException {
        ReplicationSpec spec = registry.get(name).orElse(null);
        if (spec == null) return ResponseEntity.notFound().build();
        HealthReport report = monitor.check(spec);
        return ResponseEntity.ok(report);
    }

    /**
     * Compute the drift plan without changing anything. Always safe to call.
     */
    @GetMapping("/{name}/plan")
    public ResponseEntity<?> plan(@PathVariable String name) throws SQLException {
        ReplicationSpec spec = registry.get(name).orElse(null);
        if (spec == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("replication", name,
                "actions", monitor.heal(spec, false)));
    }

    /**
     * Apply the safe additive subset now. {@code apply=false} makes this equivalent to /plan.
     */
    @PostMapping("/{name}/heal")
    public ResponseEntity<?> heal(@PathVariable String name,
                                  @RequestParam(defaultValue = "true") boolean apply)
            throws SQLException {
        ReplicationSpec spec = registry.get(name).orElse(null);
        if (spec == null) return ResponseEntity.notFound().build();
        List<ReconcileAction> actions = monitor.heal(spec, apply);
        return ResponseEntity.ok(Map.of("replication", name, "applied", apply, "actions", actions));
    }

    /** Re-point the subscription after the source credential was rotated in the store. */
    @PostMapping("/{name}/refresh-connection")
    public ResponseEntity<?> refreshConnection(@PathVariable String name) throws SQLException {
        ReplicationSpec spec = registry.get(name).orElse(null);
        if (spec == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("statement", provisioner.refreshConnection(spec)));
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<?> delete(@PathVariable String name) throws SQLException {
        ReplicationSpec spec = registry.get(name).orElse(null);
        if (spec == null) return ResponseEntity.notFound().build();
        List<String> steps = provisioner.deprovision(spec);
        registry.remove(name);
        return ResponseEntity.ok(Map.of("replication", name, "steps", steps));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(SQLException.class)
    public ResponseEntity<Map<String, String>> sqlError(SQLException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", e.getMessage(), "sqlState", String.valueOf(e.getSQLState())));
    }
}
