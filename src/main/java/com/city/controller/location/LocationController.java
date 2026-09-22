package com.city.controller.location;

import com.city.model.LocationResolveRequest;
import com.city.model.LocationResolveResponse;
import com.city.service.location.AmapLocationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 页面定位接口：浏览器传坐标，服务端调用高德完成逆地理编码。 */
@RestController
@RequestMapping("/api/v1/city/location")
public class LocationController {
    private final AmapLocationService amapLocationService;

    public LocationController(AmapLocationService amapLocationService) {
        this.amapLocationService = amapLocationService;
    }

    @PostMapping("/resolve")
    public LocationResolveResponse resolve(@RequestBody LocationResolveRequest request) {
        return amapLocationService.resolve(request.longitude(), request.latitude());
    }
}
