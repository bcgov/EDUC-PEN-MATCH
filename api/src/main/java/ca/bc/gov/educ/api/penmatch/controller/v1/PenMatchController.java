package ca.bc.gov.educ.api.penmatch.controller.v1;

import ca.bc.gov.educ.api.penmatch.endpoint.v1.PenMatchEndpoint;
import ca.bc.gov.educ.api.penmatch.mappers.v1.MatchReasonCodeMapper;
import ca.bc.gov.educ.api.penmatch.mappers.v1.PenMatchStudentMapper;
import ca.bc.gov.educ.api.penmatch.mappers.v1.PossibleMatchMapper;
import ca.bc.gov.educ.api.penmatch.service.v1.match.PenMatchService;
import ca.bc.gov.educ.api.penmatch.service.v1.match.PossibleMatchService;
import ca.bc.gov.educ.api.penmatch.struct.v1.MatchReasonCode;
import ca.bc.gov.educ.api.penmatch.struct.v1.PenMatchResult;
import ca.bc.gov.educ.api.penmatch.struct.v1.PenMatchStudent;
import ca.bc.gov.educ.api.penmatch.struct.v1.PossibleMatch;
import lombok.AccessLevel;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The type Pen match controller.
 */
@RestController
public class PenMatchController implements PenMatchEndpoint {
  /**
   * The constant mapper.
   */
  private static final PenMatchStudentMapper mapper = PenMatchStudentMapper.mapper;
  /**
   * The Pen match service.
   */
  @Getter(AccessLevel.PRIVATE)
  private final PenMatchService penMatchService;

  /**
   * The Possible match service.
   */
  @Getter(AccessLevel.PRIVATE)
  private final PossibleMatchService possibleMatchService;

  /**
   * The constant possibleMatchMapper.
   */
  private static final PossibleMatchMapper possibleMatchMapper = PossibleMatchMapper.mapper;

  /**
   * Instantiates a new Pen match controller.
   *
   * @param penMatchService      the pen match service
   * @param possibleMatchService the possible match service
   */
  @Autowired
  public PenMatchController(final PenMatchService penMatchService, PossibleMatchService possibleMatchService) {
    this.penMatchService = penMatchService;
    this.possibleMatchService = possibleMatchService;
  }

  @Override
  public PenMatchResult matchStudent(PenMatchStudent student) {
    return penMatchService.matchStudent(mapper.toPenMatchStudentDetails(student), UUID.randomUUID());
  }

  @Override
  public List<PossibleMatch> savePossibleMatches(List<PossibleMatch> possibleMatches) {
    var convertedList = possibleMatches.stream().map(possibleMatchMapper::toModel).collect(Collectors.toList());
    return getPossibleMatchService().savePossibleMatches(convertedList).stream().map(possibleMatchMapper::toStruct).collect(Collectors.toList());
  }

  @Override
  public List<PossibleMatch> getPossibleMatchesByStudentID(UUID studentID) {
    return getPossibleMatchService().getPossibleMatches(studentID).stream().map(possibleMatchMapper::toStruct).collect(Collectors.toList());

  }

  @Override
  public ResponseEntity<Void> deletePossibleMatchesByStudentIDAndMatchedStudentID(UUID studentID, UUID matchedStudentID) {
    getPossibleMatchService().deletePossibleMatches(studentID, matchedStudentID);
    return ResponseEntity.noContent().build();
  }

  @Override
  public List<String> getNicknames(String givenName) {
    return penMatchService.getNicknames(givenName).stream().map(entity -> entity.getNickname2().trim()).collect(Collectors.toList());
  }

  @Override
  public List<MatchReasonCode> getAllMatchReasonCodes() {
    return getPossibleMatchService().getAllMatchReasonCodes().stream().map(MatchReasonCodeMapper.mapper::toStruct).collect(Collectors.toList());
  }

}
