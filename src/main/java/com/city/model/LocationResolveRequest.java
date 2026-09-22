package com.city.model;

/** 用户浏览器定位得到的经纬度。 */
public record LocationResolveRequest(Double longitude, Double latitude) {
}
