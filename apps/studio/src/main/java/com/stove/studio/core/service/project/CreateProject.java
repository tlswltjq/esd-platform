package com.stove.studio.core.service.project;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.studio.core.domain.project.Project;
import com.stove.studio.core.domain.project.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class CreateProject {
    private final ProjectRepository projects;

    public Project execute(String subject, String code, String title) {
        if (projects.existsByProductCode(code))
            throw new BusinessException(ErrorCode.CONFLICT, "이미 등록된 상품 코드입니다.");
        try {
            return projects.saveAndFlush(Project.create(code, title, subject));
        } catch (DataIntegrityViolationException duplicate) {
            throw new BusinessException(ErrorCode.CONFLICT, "이미 등록된 상품 코드입니다.");
        }
    }
}
