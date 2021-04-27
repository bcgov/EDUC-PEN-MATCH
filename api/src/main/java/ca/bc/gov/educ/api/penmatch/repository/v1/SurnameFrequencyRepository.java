package ca.bc.gov.educ.api.penmatch.repository.v1;

import ca.bc.gov.educ.api.penmatch.model.v1.SurnameFrequencyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * The interface Surname frequency repository.
 */
@Repository
public interface SurnameFrequencyRepository extends JpaRepository<SurnameFrequencyEntity, String> {
  /**
   * Find all by surname starting with list.
   *
   * @param surname the surname
   * @return the list
   */
  List<SurnameFrequencyEntity> findAllBySurnameStartingWith(String surname);
}
