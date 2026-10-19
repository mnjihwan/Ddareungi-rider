import type { Place } from '../lib/types';
import { Icon } from './Icon';

export function MapPlaceChoice({place, onChoose, onClose}: {
  place: Place;
  onChoose: (role: 'origin' | 'destination', place: Place) => void;
  onClose: () => void;
}) {
  return <section className="map-place-choice" aria-label="선택한 장소">
    <button className="map-place-close" aria-label="장소 선택 닫기" onClick={onClose}><Icon name="close" size={18}/></button>
    <b>{place.name}</b><p>{place.address}</p>
    <div className="map-place-actions">
      <button onClick={()=>onChoose('origin',place)}>출발지로</button>
      <button className="primary-button" onClick={()=>onChoose('destination',place)}>도착지로</button>
    </div>
  </section>;
}
