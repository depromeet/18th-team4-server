package com.readum.model.summary.repository;

import com.readum.model.summary.entity.OpenAiBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OpenAiBatchRepository extends JpaRepository<OpenAiBatch, Long> {

    /** 주어진 상태의 배치 목록을 조회한다. collector 가 SUBMITTED 목록을 폴링할 때 사용. */
    List<OpenAiBatch> findByStatus(OpenAiBatch.Status status);
}
