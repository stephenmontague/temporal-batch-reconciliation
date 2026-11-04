package com.example.batchprocessing.core.store.repository;

import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.batchprocessing.core.store.entity.TxnRecord;

public interface TxnRepository extends JpaRepository<TxnRecord, String> {

    List<TxnRecord> findByFileIdAndStateAndLineNoBetween(String fileId, String state, int fromLine, int toLineInclusive);

    List<TxnRecord> findByFileIdAndStateIn(String fileId, Collection<String> states);

    int countByFileIdAndState(String fileId, String state);

    List<TxnRecord> findByFileIdAndStateAndErrorCodeIn(String fileId, String state, Collection<String> errorCodes);

    int countByFileIdAndStateAndErrorCodeIn(String fileId, String state, Collection<String> errorCodes);

    List<TxnRecord> findByFileIdAndState(String fileId, String state);

    List<TxnRecord> findByCounterKeyAndStateIn(String counterKey, Collection<String> states);

    int countByCounterKeyAndState(String counterKey, String state);

    Stream<TxnRecord> streamByFileIdOrderByLineNo(String fileId);

    @Modifying
    @Query("update TxnRecord t set t.state = :toState where t.counterKey = :counterKey and t.state in :fromStates")
    int moveAll(
            @Param("counterKey") String counterKey,
            @Param("fromStates") Collection<String> fromStates,
            @Param("toState") String toState);
}
