package com.stove.license.core.service;

import com.stove.license.core.domain.License;

public record StorageItem(License purchase, boolean registrable) {
}
