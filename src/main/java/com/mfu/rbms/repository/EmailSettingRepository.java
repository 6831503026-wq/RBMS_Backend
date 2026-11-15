package com.mfu.rbms.repository;

import com.mfu.rbms.model.EmailSetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailSettingRepository extends JpaRepository<EmailSetting, Long> {
}
