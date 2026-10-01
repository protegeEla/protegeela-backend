package com.condertech.protegeela.support;

import com.condertech.protegeela.shared.exception.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/support-points/places")
public class PlacesController {
    private final PlacesService places;

    public PlacesController(PlacesService places) { this.places = places; }

    public record SearchInput(@Size(max = 200) String query,
            @Pattern(regexp = "all|women_police|support_center|hospital|legal") String category,
            @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @DecimalMin("-180") @DecimalMax("180") Double longitude,
            @Min(100) @Max(50000) Integer radius) {}

    @PostMapping("/search")
    public ResponseEntity<Map<String, Object>> search(@Valid @RequestBody SearchInput input) {
        if ((input.latitude() == null) != (input.longitude() == null)
                || (input.latitude() != null && (!Double.isFinite(input.latitude()) || !Double.isFinite(input.longitude())))) {
            throw new BusinessException("Informe latitude e longitude válidas juntas.");
        }
        if (input.latitude() == null && (input.query() == null || input.query().isBlank())) {
            throw new BusinessException("Informe uma busca com cidade ou sua localização.");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(places.search(input));
    }
}
